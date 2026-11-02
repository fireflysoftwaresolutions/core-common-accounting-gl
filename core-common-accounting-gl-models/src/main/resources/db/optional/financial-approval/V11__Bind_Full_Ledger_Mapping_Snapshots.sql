-- Explicit optional successor: V1-V10 and the original mapping-definition digest keep their meaning.
DO $$ BEGIN
    IF to_regprocedure('public.gl_financial_approval_verify(uuid,uuid,uuid)') IS NULL
        OR NOT pg_has_role(current_user,'firefly_audit_guard','SET')
        OR NOT has_schema_privilege(current_user,'public','CREATE WITH GRANT OPTION')
        OR has_schema_privilege('firefly_audit_guard','public','CREATE') THEN
        RAISE EXCEPTION 'Separately delegated financial mapping installer required' USING ERRCODE='42501';
    END IF;
END $$;
GRANT CREATE ON SCHEMA public TO firefly_audit_guard;
GRANT SELECT ON public.gl_native_book,public.gl_native_account,public.gl_native_currency_admission,public.gl_native_mapping TO firefly_audit_guard;
GRANT REFERENCES(tenant_id,book_id,id,revision,definition_digest) ON public.gl_native_mapping TO firefly_audit_guard;
GRANT UPDATE ON public.gl_native_account TO firefly_audit_guard;
CREATE POLICY financial_mapping_integrity_read ON public.gl_native_book FOR SELECT TO firefly_audit_guard USING(true);
CREATE POLICY financial_mapping_integrity_read ON public.gl_native_account FOR SELECT TO firefly_audit_guard USING(true);
CREATE POLICY financial_mapping_integrity_lock ON public.gl_native_account FOR UPDATE TO firefly_audit_guard USING(true) WITH CHECK(false);
CREATE POLICY financial_mapping_integrity_read ON public.gl_native_currency_admission FOR SELECT TO firefly_audit_guard USING(true);
CREATE POLICY financial_mapping_integrity_read ON public.gl_native_mapping FOR SELECT TO firefly_audit_guard USING(true);
SET LOCAL ROLE firefly_audit_guard;

CREATE FUNCTION public.gl_ledger_mapping_snapshot_canonical_v1(body TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE r JSON; ref JSON; binding JSON; ledger JSON; leaf JSON; control JSON; currency JSON; masters JSON;
    fields TEXT[]; side TEXT; leaves TEXT[]:='{}'; ledger_ids TEXT[]:='{}'; controls JSONB:='{}'; seen TEXT[];
    account TEXT; version TEXT; tenant TEXT; book TEXT; item TEXT;
BEGIN
    IF body IS NULL OR octet_length(body) NOT BETWEEN 1 AND 1048576 OR NOT(body IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Exact bounded mapping object required' USING ERRCODE='23514';
    END IF;
    r:=body::json;
    PERFORM public.gl_native_intent_object_v1(r,ARRAY['tenant','bookId','reference','source','destination','currency']);
    tenant:=public.gl_native_intent_atom_v1(r->'tenant','uuid');book:=public.gl_native_intent_atom_v1(r->'bookId','uuid');
    fields:=ARRAY[tenant,book];ref:=r->'reference';
    PERFORM public.gl_native_intent_object_v1(ref,ARRAY['id','version','digest','purpose']);
    IF public.gl_native_intent_text_v1(ref->'purpose',100)<>'INTERNAL_CUSTOMER_TRANSFER' THEN
        RAISE EXCEPTION 'Supported internal transfer mapping required' USING ERRCODE='23514';
    END IF;
    fields:=fields||ARRAY[public.gl_native_intent_atom_v1(ref->'id','uuid'),
        public.gl_native_intent_integer_v1(ref->'version',1,9223372036854775807),
        public.gl_native_intent_atom_v1(ref->'digest','digest'),'INTERNAL_CUSTOMER_TRANSFER'];
    FOREACH side IN ARRAY ARRAY['source','destination'] LOOP
        binding:=r->side;PERFORM public.gl_native_intent_object_v1(binding,ARRAY['ledger','leaf']);ledger:=binding->'ledger';leaf:=binding->'leaf';
        PERFORM public.gl_native_intent_object_v1(ledger,ARRAY['accountId','enrollmentId','contractId']);
        account:=public.gl_native_intent_atom_v1(ledger->'accountId','uuid');
        IF account=ANY(ledger_ids) THEN RAISE EXCEPTION 'Distinct Ledger accounts required' USING ERRCODE='23514';END IF;
        ledger_ids:=array_append(ledger_ids,account);
        fields:=fields||ARRAY[account,public.gl_native_intent_atom_v1(ledger->'enrollmentId','uuid'),public.gl_native_intent_atom_v1(ledger->'contractId','uuid')];
        PERFORM public.gl_native_intent_object_v1(leaf,ARRAY['tenant','bookId','accountId','version','role','sourceType','controlAncestors']);
        account:=public.gl_native_intent_atom_v1(leaf->'accountId','uuid');
        IF public.gl_native_intent_atom_v1(leaf->'tenant','uuid')<>tenant OR public.gl_native_intent_atom_v1(leaf->'bookId','uuid')<>book
            OR public.gl_native_intent_text_v1(leaf->'role',100)<>'POSTING' OR public.gl_native_intent_text_v1(leaf->'sourceType',100)<>'LIABILITY'
            OR account=ANY(leaves) OR json_typeof(leaf->'controlAncestors')<>'array' OR json_array_length(leaf->'controlAncestors') NOT BETWEEN 1 AND 32 THEN
            RAISE EXCEPTION 'Exact same-book liability leaves required' USING ERRCODE='23514';
        END IF;
        leaves:=array_append(leaves,account);
        fields:=fields||ARRAY[tenant,book,account,public.gl_native_intent_integer_v1(leaf->'version',1,9223372036854775807),
            'POSTING','LIABILITY',json_array_length(leaf->'controlAncestors')::text];
        seen:='{}';
        FOR control IN SELECT * FROM json_array_elements(leaf->'controlAncestors') LOOP
            PERFORM public.gl_native_intent_object_v1(control,ARRAY['accountId','version']);
            account:=public.gl_native_intent_atom_v1(control->'accountId','uuid');version:=public.gl_native_intent_integer_v1(control->'version',1,9223372036854775807);
            IF account=ANY(seen) OR controls?account AND controls->>account<>version THEN
                RAISE EXCEPTION 'Unchanged complete control path required' USING ERRCODE='23514';
            END IF;
            seen:=array_append(seen,account);controls:=controls||jsonb_build_object(account,version);fields:=fields||ARRAY[account,version];
        END LOOP;
    END LOOP;
    FOREACH item IN ARRAY leaves LOOP
        IF controls?item THEN RAISE EXCEPTION 'Posting leaves cannot be controls' USING ERRCODE='23514';END IF;
    END LOOP;
    currency:=r->'currency';PERFORM public.gl_native_intent_object_v1(currency,ARRAY['id','version','digest','ledgerPolicy','scale','masters','admissionId','admissionVersion']);
    masters:=currency->'masters';PERFORM public.gl_native_intent_object_v1(masters,ARRAY['policyId','currencyId','isoCode','version','digest','encodingVersion']);
    fields:=fields||ARRAY[public.gl_native_intent_atom_v1(currency->'id','uuid'),public.gl_native_intent_integer_v1(currency->'version',1,9223372036854775807),
        public.gl_native_intent_atom_v1(currency->'digest','digest'),public.gl_native_intent_text_v1(currency->'ledgerPolicy',100),
        public.gl_native_intent_integer_v1(currency->'scale',0,10),public.gl_native_intent_atom_v1(masters->'policyId','uuid'),
        public.gl_native_intent_atom_v1(masters->'currencyId','uuid'),public.gl_native_intent_atom_v1(masters->'isoCode','iso'),
        public.gl_native_intent_integer_v1(masters->'version',1,9223372036854775807),public.gl_native_intent_atom_v1(masters->'digest','digest'),
        public.gl_native_intent_atom_v1(masters->'encodingVersion','encoding'),public.gl_native_intent_atom_v1(currency->'admissionId','uuid'),
        public.gl_native_intent_integer_v1(currency->'admissionVersion',1,9223372036854775807)];
    RETURN public.gl_native_intent_frame_v1('GL_LEDGER_MAPPING_SNAPSHOT_V1',fields);
END $$;

CREATE TABLE public.gl_financial_approval_mapping (
    approval_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,book_id UUID NOT NULL,snapshot_id UUID NOT NULL UNIQUE,
    snapshot_format TEXT NOT NULL CHECK(snapshot_format='GL_LEDGER_MAPPING_SNAPSHOT_V1'),
    mapping_id UUID NOT NULL,mapping_version BIGINT NOT NULL,definition_digest TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,canonical_snapshot TEXT NOT NULL,snapshot_sha256 TEXT NOT NULL CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    requested_event_id UUID NOT NULL,requested_record_hash TEXT NOT NULL CHECK(requested_record_hash ~ '^[0-9a-f]{64}$'),
    CHECK(snapshot_id<>'00000000-0000-0000-0000-000000000000'),
    CHECK(canonical_snapshot=public.gl_ledger_mapping_snapshot_canonical_v1(snapshot_json)),
    CHECK(snapshot_sha256=encode(sha256(convert_to(canonical_snapshot,'UTF8')),'hex')),
    FOREIGN KEY(tenant_id,book_id,approval_id) REFERENCES public.gl_financial_approval_definition(tenant_id,book_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,book_id,mapping_id,mapping_version,definition_digest)
        REFERENCES public.gl_native_mapping(tenant_id,book_id,id,revision,definition_digest) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE public.gl_financial_approval_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_financial_approval_mapping FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.gl_financial_approval_mapping FROM PUBLIC,firefly_audit_writer,firefly_audit_reader;
CREATE POLICY mapping_guard_integrity ON public.gl_financial_approval_mapping TO firefly_audit_guard USING(true) WITH CHECK(true);
CREATE POLICY mapping_owner_read ON public.gl_financial_approval_mapping FOR SELECT TO firefly_audit_writer
    USING(public.gl_native_context_matches(tenant_id,book_id,ARRAY['read']) OR public.gl_financial_approval_write_context(tenant_id,approval_id,NULL));
CREATE POLICY mapping_owner_insert ON public.gl_financial_approval_mapping FOR INSERT TO firefly_audit_writer
    WITH CHECK(public.gl_financial_approval_write_context(tenant_id,approval_id,requested_event_id));
GRANT SELECT,INSERT ON public.gl_financial_approval_mapping TO firefly_audit_writer;
CREATE TRIGGER financial_mapping_immutable BEFORE UPDATE OR DELETE ON public.gl_financial_approval_mapping
    FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_immutable();

CREATE FUNCTION public.gl_financial_mapping_binding_canonical_v1(m public.gl_financial_approval_mapping) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
    SELECT public.gl_native_intent_frame_v1('GL_FINANCIAL_MAPPING_BINDING_V1',ARRAY[m.approval_id::text,m.snapshot_id::text,m.snapshot_format,
        m.snapshot_sha256,m.mapping_id::text,m.mapping_version::text,m.definition_digest])
$$;
CREATE FUNCTION public.gl_financial_approval_canonical_v2(d public.gl_financial_approval_definition,t public.gl_financial_approval_transition,
    a firefly_audit.audit_event,p public.gl_financial_approval_transition) RETURNS TEXT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
    SELECT public.gl_financial_approval_canonical_v1(d,t,a,p)||coalesce((SELECT public.gl_financial_mapping_binding_canonical_v1(m)
        FROM public.gl_financial_approval_mapping m WHERE m.tenant_id=d.tenant_id AND m.book_id=d.book_id AND m.approval_id=d.id),'')
$$;

CREATE FUNCTION public.gl_ledger_mapping_posting_matches_v1(snapshot_body TEXT,posting_body TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE snapshot JSON;request JSON;source JSON;currency JSON;binding JSON;leaf JSON;line JSON;side TEXT;ordinal INTEGER:=0;item TEXT;
BEGIN
    PERFORM public.gl_ledger_mapping_snapshot_canonical_v1(snapshot_body);snapshot:=snapshot_body::json;
    PERFORM public.gl_native_request_fields_v1((snapshot->>'tenant')::uuid,(snapshot->>'bookId')::uuid,posting_body);request:=posting_body::json;
    source:=request->'source';currency:=snapshot->'currency';
    IF request->>'mappingId'<>snapshot->'reference'->>'id' OR (request->>'mappingVersion')::bigint<>(snapshot->'reference'->>'version')::bigint
        OR request->>'mappingDigest'<>snapshot->'reference'->>'digest' OR source->>'owner'<>'LEDGER' OR source->>'eventKind'<>'COMMIT'
        OR (source->>'revision')::bigint<>1 OR (source->>'effectOrdinal')::integer<>0 OR json_array_length(request->'entries')<>1
        OR json_array_length(request->'entries'->0->'lines')<>2 THEN RETURN false;END IF;
    FOREACH item IN ARRAY ARRAY['objectReference','effectReference','financialReceiptReference','provenanceReference'] LOOP
        PERFORM public.gl_native_intent_atom_v1(source->item,'uuid');
    END LOOP;
    FOREACH side IN ARRAY ARRAY['source','destination'] LOOP
        binding:=snapshot->side;leaf:=binding->'leaf';line:=request->'entries'->0->'lines'->ordinal;
        IF line->>'lineKey'<>(CASE ordinal WHEN 0 THEN '001' ELSE '002' END) OR line->>'accountId'<>leaf->>'accountId'
            OR (line->>'expectedAccountVersion')::bigint<>(leaf->>'version')::bigint
            OR line->>'currencyAdmissionId'<>currency->>'admissionId' OR (line->>'expectedCurrencyVersion')::bigint<>(currency->>'admissionVersion')::bigint
            OR (line->'currency')::jsonb IS DISTINCT FROM (currency->'masters')::jsonb
            OR (line->>'postedScale')::integer<>(currency->>'scale')::integer
            OR line->>'side'<>(CASE ordinal WHEN 0 THEN 'DR' ELSE 'CR' END) OR line->>'memo'<>'Internal customer transfer' THEN RETURN false;END IF;
        ordinal:=ordinal+1;
    END LOOP;
    RETURN request->'entries'->0->'lines'->0->>'amountMinor'=request->'entries'->0->'lines'->1->>'amountMinor';
END $$;

CREATE FUNCTION public.gl_financial_approval_mapping_integrity(tenant UUID,book UUID,approval UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
    SELECT EXISTS(SELECT 1 FROM public.gl_financial_approval_mapping m
        JOIN public.gl_financial_approval_definition d ON d.tenant_id=m.tenant_id AND d.book_id=m.book_id AND d.id=m.approval_id
        WHERE m.tenant_id=tenant AND m.book_id=book AND m.approval_id=approval
        AND m.requested_event_id=d.requested_event_id AND m.requested_record_hash=d.requested_record_hash
        AND m.mapping_id::text=d.original_json::json->>'mappingId' AND m.mapping_version=(d.original_json::json->>'mappingVersion')::bigint
        AND m.definition_digest=d.original_json::json->>'mappingDigest'
        AND m.canonical_snapshot=public.gl_ledger_mapping_snapshot_canonical_v1(m.snapshot_json)
        AND m.snapshot_sha256=encode(sha256(convert_to(m.canonical_snapshot,'UTF8')),'hex')
        AND m.snapshot_json::json->>'tenant'=tenant::text AND m.snapshot_json::json->>'bookId'=book::text
        AND m.snapshot_json::json->'reference'->>'id'=m.mapping_id::text
        AND (m.snapshot_json::json->'reference'->>'version')::bigint=m.mapping_version
        AND m.snapshot_json::json->'reference'->>'digest'=m.definition_digest
        AND public.gl_ledger_mapping_posting_matches_v1(m.snapshot_json,d.original_json))
$$;

CREATE FUNCTION public.gl_financial_approval_mapping_current(tenant UUID,book UUID,approval UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE m public.gl_financial_approval_mapping; d public.gl_financial_approval_definition;
    snapshot JSON;binding JSON;leaf JSON;currency JSON;masters JSON;control JSON;side TEXT;parent UUID;
    account public.gl_native_account; representation public.gl_native_currency_admission;
BEGIN
    IF NOT(public.gl_native_context_matches(tenant,book,ARRAY['read','post'])
        OR public.gl_financial_approval_write_context(tenant,approval,NULL)) THEN RETURN false;END IF;
    SELECT * INTO m FROM public.gl_financial_approval_mapping x WHERE x.tenant_id=tenant AND x.book_id=book AND x.approval_id=approval;
    IF NOT FOUND OR NOT public.gl_financial_approval_mapping_integrity(tenant,book,approval) THEN RETURN false;END IF;
    SELECT * INTO d FROM public.gl_financial_approval_definition x WHERE x.tenant_id=tenant AND x.book_id=book AND x.id=approval;
    IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.gl_native_book b WHERE b.tenant_id=tenant AND b.id=book
        AND b.lifecycle='ACTIVE' AND b.control_version=(d.original_json::json->>'expectedBookVersion')::bigint) THEN RETURN false;END IF;
    snapshot:=m.snapshot_json::json;currency:=snapshot->'currency';masters:=currency->'masters';
    SELECT * INTO representation FROM public.gl_native_currency_admission c
        WHERE c.tenant_id=tenant AND c.book_id=book AND c.id=(currency->>'admissionId')::uuid;
    IF NOT FOUND OR representation.lifecycle<>'ACTIVE' OR representation.control_version<>(currency->>'admissionVersion')::bigint
        OR representation.posted_scale<>(currency->>'scale')::integer OR representation.policy_id::text<>masters->>'policyId'
        OR representation.currency_id::text<>masters->>'currencyId' OR representation.iso_code<>masters->>'isoCode'
        OR representation.policy_version<>(masters->>'version')::bigint OR representation.policy_digest<>masters->>'digest'
        OR representation.policy_encoding<>masters->>'encodingVersion' THEN RETURN false;END IF;
    FOREACH side IN ARRAY ARRAY['source','destination'] LOOP
        binding:=snapshot->side;leaf:=binding->'leaf';
        SELECT * INTO account FROM public.gl_native_account a WHERE a.tenant_id=tenant AND a.book_id=book AND a.account_id=(leaf->>'accountId')::uuid;
        IF NOT FOUND OR account.kind<>'POSTING' OR account.source_type<>'LIABILITY' OR account.lifecycle<>'ACTIVE'
            OR account.control_version<>(leaf->>'version')::bigint OR account.currency_admission_id<>representation.id
            OR account.source_currency<>representation.iso_code THEN RETURN false;END IF;
        parent:=account.parent_account_id;
        FOR control IN SELECT * FROM json_array_elements(leaf->'controlAncestors') LOOP
            IF parent IS NULL OR parent::text<>control->>'accountId' THEN RETURN false;END IF;
            SELECT * INTO account FROM public.gl_native_account a WHERE a.tenant_id=tenant AND a.book_id=book AND a.account_id=parent;
            IF NOT FOUND OR account.kind<>'CONTROL' OR account.lifecycle<>'ACTIVE' OR account.control_version<>(control->>'version')::bigint THEN RETURN false;END IF;
            parent:=account.parent_account_id;
        END LOOP;
        IF parent IS NOT NULL THEN RETURN false;END IF;
    END LOOP;
    RETURN true;
END $$;

-- Preserve all original unbound history while reconstructing the explicit bound successor.
CREATE OR REPLACE FUNCTION public.gl_financial_approval_verify(tenant UUID,book UUID,approval UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE d public.gl_financial_approval_definition; c public.gl_financial_approval_control;
    t public.gl_financial_approval_transition; p public.gl_financial_approval_transition;
    checked public.gl_financial_approval_transition; a firefly_audit.audit_event; count_rows INTEGER:=0;
BEGIN
    IF NOT (public.gl_native_context_matches(tenant,book,ARRAY['read','post'])
        OR public.gl_financial_approval_write_context(tenant,approval,NULL)) THEN RETURN false; END IF;
    SELECT * INTO d FROM public.gl_financial_approval_definition x WHERE x.tenant_id=tenant AND x.book_id=book AND x.id=approval;
    IF NOT FOUND THEN RETURN false; END IF;
    IF public.gl_native_context_matches(tenant,book,ARRAY['post'])
        AND (d.posting_action_id::text IS DISTINCT FROM current_setting('firefly.gl.action',true)
        OR d.maker_issuer IS DISTINCT FROM current_setting('firefly.gl.actor_issuer',true)
        OR d.maker_subject IS DISTINCT FROM current_setting('firefly.gl.actor_subject',true)) THEN RETURN false; END IF;
    SELECT * INTO c FROM public.gl_financial_approval_control x WHERE x.tenant_id=tenant AND x.book_id=book AND x.approval_id=approval;
    IF NOT FOUND OR d.canonical_intent IS DISTINCT FROM public.gl_native_request_canonical_v1(tenant,book,d.original_json,false)
        OR d.canonical_economic_intent IS DISTINCT FROM public.gl_native_request_canonical_v1(tenant,book,d.original_json,true)
        OR d.intent_digest<>encode(sha256(convert_to(d.canonical_intent,'UTF8')),'hex')
        OR d.economic_intent_digest<>encode(sha256(convert_to(d.canonical_economic_intent,'UTF8')),'hex')
        OR d.source_claim_key<>public.gl_native_request_source_key_v1(tenant,book,d.original_json)
        OR d.posting_action_id::text<>(d.original_json::json->>'actionId')
        OR d.business_until_epoch_millis>(d.original_json::json->>'notAfterEpochMillis')::bigint THEN RETURN false; END IF;
    FOR t IN SELECT * FROM public.gl_financial_approval_transition x
        WHERE x.tenant_id=tenant AND x.book_id=book AND x.approval_id=approval ORDER BY revision LOOP
        IF t.revision<>count_rows OR t.revision>2 THEN RETURN false; END IF;
        SELECT * INTO a FROM firefly_audit.audit_event e WHERE e.resource_tenant_id=tenant AND e.event_id=t.event_id;
        IF NOT FOUND OR a.service<>'core-common-accounting-gl' OR a.resource_type<>'financial-approval' OR a.resource_id<>approval
            OR a.sequence<>t.revision+1 OR a.record_hash<>t.event_record_hash OR a.action<>'accounting.gl.approval.'||t.operation
            OR a.outcome<>'COMPLETED' OR a.process_id<>approval OR a.correlation_id<>t.event_id
            OR a.causation_id IS DISTINCT FROM t.predecessor_event_id
            OR a.policy_revision<>t.audit_policy_revision OR a.evidence_digest<>t.transition_digest
            OR ROW(a.actor_issuer,a.actor_subject,a.actor_staff_realm,a.actor_staff_id)
                IS DISTINCT FROM ROW(t.actor_issuer,t.actor_subject,t.actor_staff_realm,t.actor_staff_id)
            OR a.actor_platform_tenant_id<>d.maker_platform_tenant
            OR a.recorded_nanos%1000<>0
            OR t.recorded_at IS DISTINCT FROM to_timestamp(a.recorded_seconds)+(a.recorded_nanos/1000)*INTERVAL '1 microsecond'
            OR t.canonical_transition IS DISTINCT FROM public.gl_financial_approval_canonical_v2(d,t,a,p)
            OR t.transition_digest<>encode(sha256(convert_to(t.canonical_transition,'UTF8')),'hex')
            OR NOT EXISTS(SELECT 1 FROM firefly_audit.audit_outbox o WHERE o.resource_tenant_id=tenant AND o.event_id=t.event_id
                AND o.service=a.service AND o.resource_type=a.resource_type AND o.resource_id=approval
                AND o.sequence=a.sequence AND o.record_hash=a.record_hash AND o.intent='AUDIT_EXPORT') THEN RETURN false; END IF;
        PERFORM firefly_audit_private.require_record_hash(a);
        IF t.revision=0 THEN
            IF t.operation<>'request' OR t.state<>'REQUESTED' OR t.predecessor_event_id IS NOT NULL
                OR t.event_id<>d.requested_event_id OR t.event_record_hash<>d.requested_record_hash
                OR ROW(a.actor_issuer,a.actor_subject,a.actor_client_id,a.actor_platform_tenant_id,a.actor_staff_realm,a.actor_staff_id)
                    IS DISTINCT FROM ROW(d.maker_issuer,d.maker_subject,d.maker_client_id,d.maker_platform_tenant,d.maker_staff_realm,d.maker_staff_id)
                OR d.business_until_epoch_millis<=a.occurred_seconds*1000+a.occurred_nanos/1000000 THEN RETURN false; END IF;
        ELSE
            IF t.predecessor_event_id IS DISTINCT FROM p.event_id OR p.state='REVOKED' THEN RETURN false; END IF;
            IF t.operation='check' THEN
                IF t.revision<>1 OR p.state<>'REQUESTED' OR t.state<>'APPROVED'
                    OR (t.actor_issuer=d.maker_issuer AND t.actor_subject=d.maker_subject)
                    OR (t.actor_staff_realm=d.maker_staff_realm AND t.actor_staff_id=d.maker_staff_id)
                    OR d.business_until_epoch_millis<=a.occurred_seconds*1000+a.occurred_nanos/1000000 THEN RETURN false; END IF;
                checked:=t;
            ELSIF t.operation='revoke' THEN
                IF t.state<>'REVOKED' OR NOT (
                    ROW(t.actor_issuer,t.actor_subject,t.actor_staff_realm,t.actor_staff_id)
                        IS NOT DISTINCT FROM ROW(d.maker_issuer,d.maker_subject,d.maker_staff_realm,d.maker_staff_id)
                    OR (checked.event_id IS NOT NULL AND ROW(t.actor_issuer,t.actor_subject,t.actor_staff_realm,t.actor_staff_id)
                        IS NOT DISTINCT FROM ROW(checked.actor_issuer,checked.actor_subject,checked.actor_staff_realm,checked.actor_staff_id))) THEN RETURN false; END IF;
            ELSE RETURN false; END IF;
        END IF;
        p:=t;count_rows:=count_rows+1;
    END LOOP;
    RETURN count_rows=c.revision+1 AND p.event_id=c.last_event_id AND p.event_record_hash=c.last_record_hash
        AND p.transition_digest=c.transition_digest AND p.state=c.state
        AND (CASE WHEN checked.event_id IS NULL THEN c.approved_until_epoch_millis IS NULL AND c.checker_issuer IS NULL
            ELSE c.approved_until_epoch_millis=d.business_until_epoch_millis
                AND ROW(c.checker_issuer,c.checker_subject,c.checker_staff_realm,c.checker_staff_id)
                    IS NOT DISTINCT FROM ROW(checked.actor_issuer,checked.actor_subject,checked.actor_staff_realm,checked.actor_staff_id) END)
        AND (SELECT count(*) FROM firefly_audit.audit_event e WHERE e.resource_tenant_id=tenant
            AND e.service='core-common-accounting-gl' AND e.resource_type='financial-approval' AND e.resource_id=approval)=count_rows
        AND EXISTS(SELECT 1 FROM firefly_audit.audit_head h WHERE h.resource_tenant_id=tenant
            AND h.service='core-common-accounting-gl' AND h.resource_type='financial-approval' AND h.resource_id=approval
            AND h.last_sequence=count_rows AND h.last_hash=c.last_record_hash)
        AND (NOT EXISTS(SELECT 1 FROM public.gl_financial_approval_mapping m WHERE m.tenant_id=tenant AND m.book_id=book AND m.approval_id=approval)
            OR public.gl_financial_approval_mapping_integrity(tenant,book,approval));
END $$;


CREATE FUNCTION public.gl_financial_mapping_binding_complete() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$ BEGIN
    IF NOT public.gl_financial_approval_write_context(NEW.tenant_id,NEW.approval_id,NEW.requested_event_id)
        OR NOT public.gl_financial_approval_mapping_integrity(NEW.tenant_id,NEW.book_id,NEW.approval_id)
        OR NOT public.gl_financial_approval_verify(NEW.tenant_id,NEW.book_id,NEW.approval_id) THEN
        RAISE EXCEPTION 'Original complete owner mapping and approval root required' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER financial_mapping_complete AFTER INSERT ON public.gl_financial_approval_mapping
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_financial_mapping_binding_complete();

-- This gate runs after the original native admission statement has acquired the
-- book/period/leaf/currency gates. Approval revocation and ancestor control changes
-- cannot complete behind an admitted exact posting while its owner root is open.
CREATE FUNCTION public.gl_financial_mapping_post_admission() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE d public.gl_financial_approval_definition;c public.gl_financial_approval_control;
    m public.gl_financial_approval_mapping;control_id UUID;observed BIGINT;
BEGIN
    IF NEW.kind<>'POST' OR NEW.original_request::json->'source'->>'owner'<>'LEDGER' THEN RETURN NULL;END IF;
    SELECT * INTO d FROM public.gl_financial_approval_definition x WHERE x.tenant_id=NEW.tenant_id AND x.book_id=NEW.book_id
        AND x.posting_action_id=NEW.action_id AND x.intent_digest=NEW.intent_digest AND x.canonical_intent=NEW.canonical_intent
        AND x.maker_issuer=NEW.actor_issuer AND x.maker_subject=NEW.actor_subject;
    IF NOT FOUND THEN RAISE EXCEPTION 'Full current owner mapping approval required' USING ERRCODE='42501';END IF;
    SELECT * INTO c FROM public.gl_financial_approval_control x WHERE x.tenant_id=d.tenant_id AND x.book_id=d.book_id AND x.approval_id=d.id FOR SHARE;
    IF NOT FOUND OR c.state<>'APPROVED' OR c.revision<>1 THEN
        RAISE EXCEPTION 'Current independent owner approval required after waits' USING ERRCODE='42501';
    END IF;
    SELECT * INTO m FROM public.gl_financial_approval_mapping x WHERE x.tenant_id=d.tenant_id AND x.book_id=d.book_id AND x.approval_id=d.id;
    IF NOT FOUND THEN RAISE EXCEPTION 'No unbound mapping promotion permitted' USING ERRCODE='42501';END IF;
    FOR control_id IN SELECT DISTINCT (v->>'accountId')::uuid FROM (
        SELECT v FROM json_array_elements(m.snapshot_json::json->'source'->'leaf'->'controlAncestors') v
        UNION ALL SELECT v FROM json_array_elements(m.snapshot_json::json->'destination'->'leaf'->'controlAncestors') v
    ) controls ORDER BY 1 LOOP
        PERFORM 1 FROM public.gl_native_account a WHERE a.tenant_id=NEW.tenant_id AND a.book_id=NEW.book_id AND a.account_id=control_id FOR SHARE;
    END LOOP;
    observed:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
    IF d.business_until_epoch_millis<=observed OR c.approved_until_epoch_millis IS NULL OR c.approved_until_epoch_millis<=observed
        OR NEW.authority_until_epoch_millis<=observed OR NEW.not_after_epoch_millis<=observed
        OR NOT public.gl_native_context_matches(NEW.tenant_id,NEW.book_id,ARRAY['post'])
        OR NOT public.gl_financial_approval_mapping_current(NEW.tenant_id,NEW.book_id,d.id)
        OR NOT public.gl_financial_approval_verify(NEW.tenant_id,NEW.book_id,d.id) THEN
        RAISE EXCEPTION 'Exact live mapped financial admission required after waits' USING ERRCODE='42501';
    END IF;
    RETURN NULL;
END $$;

REVOKE ALL ON FUNCTION public.gl_ledger_mapping_snapshot_canonical_v1(TEXT),
    public.gl_ledger_mapping_posting_matches_v1(TEXT,TEXT),
    public.gl_financial_mapping_binding_canonical_v1(public.gl_financial_approval_mapping),
    public.gl_financial_approval_canonical_v2(public.gl_financial_approval_definition,public.gl_financial_approval_transition,firefly_audit.audit_event,public.gl_financial_approval_transition),
    public.gl_financial_approval_mapping_integrity(UUID,UUID,UUID),public.gl_financial_approval_mapping_current(UUID,UUID,UUID),
    public.gl_financial_mapping_binding_complete(),public.gl_financial_mapping_post_admission() FROM PUBLIC,firefly_audit_writer,firefly_audit_reader;
GRANT EXECUTE ON FUNCTION public.gl_ledger_mapping_snapshot_canonical_v1(TEXT),public.gl_financial_approval_mapping_current(UUID,UUID,UUID) TO firefly_audit_writer;
DO $$ BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION public.gl_financial_mapping_post_admission() TO %I',session_user);
END $$;
RESET ROLE;
CREATE TRIGGER gl_z_financial_mapping_admission AFTER INSERT ON public.gl_native_journal_action
    FOR EACH ROW EXECUTE FUNCTION public.gl_financial_mapping_post_admission();
SET LOCAL ROLE firefly_audit_guard;
DO $$ BEGIN
    EXECUTE format('REVOKE EXECUTE ON FUNCTION public.gl_financial_mapping_post_admission() FROM %I',session_user);
END $$;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM firefly_audit_guard;
