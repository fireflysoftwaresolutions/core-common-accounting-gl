-- Explicit owner opt-in after V1-V9 and both genuine immutable-audit installation scripts.
-- This location is never added to the application's default Flyway locations.
DO $$ BEGIN
    IF to_regprocedure('public.firefly_audit_require_completed(uuid,uuid,bigint,text,boolean)') IS NULL
        OR NOT pg_has_role(current_user,'firefly_audit_guard','SET')
        OR NOT has_schema_privilege(current_user,'public','CREATE WITH GRANT OPTION')
        OR has_schema_privilege('firefly_audit_guard','public','CREATE') THEN
        RAISE EXCEPTION 'Separately delegated completed-audit installer required' USING ERRCODE='42501';
    END IF;
END $$;
GRANT CREATE ON SCHEMA public TO firefly_audit_guard;
SET LOCAL ROLE firefly_audit_guard;

CREATE TABLE public.gl_financial_approval_definition (
    id UUID PRIMARY KEY CHECK(id<>'00000000-0000-0000-0000-000000000000'),
    tenant_id UUID NOT NULL, book_id UUID NOT NULL, posting_action_id UUID NOT NULL,
    original_json TEXT NOT NULL CHECK(octet_length(original_json) BETWEEN 1 AND 1048576),
    canonical_intent TEXT NOT NULL, intent_digest TEXT NOT NULL CHECK(intent_digest ~ '^[0-9a-f]{64}$'),
    canonical_economic_intent TEXT NOT NULL, economic_intent_digest TEXT NOT NULL CHECK(economic_intent_digest ~ '^[0-9a-f]{64}$'),
    source_claim_key TEXT NOT NULL CHECK(source_claim_key ~ '^[0-9a-f]{64}$'),
    maker_issuer TEXT NOT NULL, maker_subject TEXT NOT NULL, maker_client_id TEXT NOT NULL,
    maker_platform_tenant TEXT NOT NULL, maker_staff_realm TEXT NOT NULL, maker_staff_id UUID NOT NULL,
    business_until_epoch_millis BIGINT NOT NULL CHECK(business_until_epoch_millis BETWEEN -2208988800000 AND 253402300799999),
    requested_event_id UUID NOT NULL, requested_record_hash TEXT NOT NULL CHECK(requested_record_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE(tenant_id,book_id,id),
    UNIQUE(tenant_id,book_id,posting_action_id,maker_issuer,maker_subject)
);
CREATE TABLE public.gl_financial_approval_transition (
    event_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, book_id UUID NOT NULL, approval_id UUID NOT NULL,
    operation TEXT NOT NULL CHECK(operation IN ('request','check','revoke')),
    revision BIGINT NOT NULL CHECK(revision BETWEEN 0 AND 2), state TEXT NOT NULL CHECK(state IN ('REQUESTED','APPROVED','REVOKED')),
    actor_issuer TEXT NOT NULL, actor_subject TEXT NOT NULL, actor_staff_realm TEXT NOT NULL, actor_staff_id UUID NOT NULL,
    canonical_transition TEXT NOT NULL, transition_digest TEXT NOT NULL CHECK(transition_digest ~ '^[0-9a-f]{64}$'),
    predecessor_event_id UUID, event_record_hash TEXT NOT NULL CHECK(event_record_hash ~ '^[0-9a-f]{64}$'), recorded_at TIMESTAMPTZ NOT NULL,
    authorization_policy_revision TEXT NOT NULL, authorization_decision_id TEXT NOT NULL,
    audit_policy_revision TEXT NOT NULL, audit_decision_id TEXT NOT NULL,
    account_decision_digest TEXT NOT NULL CHECK(account_decision_digest ~ '^[0-9a-f]{64}$'),
    UNIQUE(tenant_id,book_id,approval_id,revision),
    FOREIGN KEY(tenant_id,book_id,approval_id) REFERENCES public.gl_financial_approval_definition(tenant_id,book_id,id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.gl_financial_approval_control (
    approval_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, book_id UUID NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('REQUESTED','APPROVED','REVOKED')), revision BIGINT NOT NULL CHECK(revision BETWEEN 0 AND 2),
    approved_until_epoch_millis BIGINT, checker_issuer TEXT, checker_subject TEXT, checker_staff_realm TEXT, checker_staff_id UUID,
    last_event_id UUID NOT NULL, last_record_hash TEXT NOT NULL CHECK(last_record_hash ~ '^[0-9a-f]{64}$'),
    transition_digest TEXT NOT NULL CHECK(transition_digest ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY(tenant_id,book_id,approval_id) REFERENCES public.gl_financial_approval_definition(tenant_id,book_id,id)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(last_event_id) REFERENCES public.gl_financial_approval_transition(event_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK((approved_until_epoch_millis IS NULL AND checker_issuer IS NULL AND checker_subject IS NULL
        AND checker_staff_realm IS NULL AND checker_staff_id IS NULL)
        OR (approved_until_epoch_millis IS NOT NULL AND checker_issuer IS NOT NULL AND checker_subject IS NOT NULL
        AND checker_staff_realm IS NOT NULL AND checker_staff_id IS NOT NULL))
);

-- A completed audit append remains closed. Only this paired owner graph may follow
-- that exact fresh event on its original physical transaction and finite native clock.
CREATE FUNCTION public.gl_financial_approval_write_context(tenant UUID,approval UUID,event UUID) RETURNS BOOLEAN
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
    SELECT session_user='firefly_audit_writer' AND EXISTS(
        SELECT 1 FROM firefly_audit_private.context c
        JOIN firefly_audit_private.completion done ON done.nonce=c.nonce
        WHERE c.xid=pg_current_xact_id_if_assigned() AND c.backend_pid=pg_backend_pid()
        AND c.db_session_user=session_user AND c.new_event
        AND c.resource_tenant_id=tenant AND c.service='core-common-accounting-gl'
        AND c.resource_type='financial-approval' AND c.resource_id=approval
        AND (event IS NULL OR c.event_id=event)
        AND clock_timestamp()>=c.installed_at AND clock_timestamp()<c.until_time)
$$;

CREATE FUNCTION public.gl_financial_approval_canonical_v1(
    d public.gl_financial_approval_definition,t public.gl_financial_approval_transition,
    a firefly_audit.audit_event,p public.gl_financial_approval_transition) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
    SELECT public.gl_native_intent_frame_v1('GL_FINANCIAL_APPROVAL_TRANSITION_V1',ARRAY[
        d.tenant_id::text,d.book_id::text,d.id::text,d.posting_action_id::text,d.intent_digest,d.economic_intent_digest,
        d.source_claim_key,encode(sha256(convert_to(d.original_json,'UTF8')),'hex'),
        d.maker_issuer,d.maker_subject,d.maker_client_id,d.maker_platform_tenant,d.maker_staff_realm,d.maker_staff_id::text,
        d.business_until_epoch_millis::text,t.operation,t.revision::text,coalesce(p.state,''),t.state,
        CASE WHEN t.operation='revoke' THEN '0' ELSE d.business_until_epoch_millis::text END,
        a.actor_issuer,a.actor_subject,a.actor_client_id,a.actor_platform_tenant_id,a.actor_staff_realm,a.actor_staff_id::text,
        coalesce(p.event_id::text,''),coalesce(p.event_record_hash,''),t.authorization_policy_revision,t.authorization_decision_id,
        t.audit_policy_revision,t.audit_decision_id])
        ||public.gl_native_intent_frame_v1('GL_APPROVAL_ACCOUNT_PROVENANCE_V1',ARRAY[t.account_decision_digest])
$$;

CREATE FUNCTION public.gl_financial_approval_verify(tenant UUID,book UUID,approval UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE d public.gl_financial_approval_definition; c public.gl_financial_approval_control;
    t public.gl_financial_approval_transition; p public.gl_financial_approval_transition;
    checked public.gl_financial_approval_transition; a firefly_audit.audit_event; count_rows INTEGER:=0;
BEGIN
    IF NOT (public.gl_native_context_matches(tenant,book,ARRAY['read'])
        OR public.gl_financial_approval_write_context(tenant,approval,NULL)) THEN RETURN false; END IF;
    SELECT * INTO d FROM public.gl_financial_approval_definition x WHERE x.tenant_id=tenant AND x.book_id=book AND x.id=approval;
    IF NOT FOUND THEN RETURN false; END IF;
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
            OR t.canonical_transition IS DISTINCT FROM public.gl_financial_approval_canonical_v1(d,t,a,p)
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
            AND h.last_sequence=count_rows AND h.last_hash=c.last_record_hash);
END $$;

CREATE FUNCTION public.gl_financial_approval_immutable() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN
    RAISE EXCEPTION 'Immutable financial approval original required' USING ERRCODE='42501';
END $$;
CREATE TRIGGER gl_approval_definition_immutable BEFORE UPDATE OR DELETE ON public.gl_financial_approval_definition
    FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_immutable();
CREATE TRIGGER gl_approval_transition_immutable BEFORE UPDATE OR DELETE ON public.gl_financial_approval_transition
    FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_immutable();
CREATE TRIGGER gl_approval_control_no_delete BEFORE DELETE ON public.gl_financial_approval_control
    FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_immutable();
CREATE FUNCTION public.gl_financial_approval_control_advance() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN
    IF ROW(NEW.approval_id,NEW.tenant_id,NEW.book_id) IS DISTINCT FROM ROW(OLD.approval_id,OLD.tenant_id,OLD.book_id)
        OR NEW.revision<>OLD.revision+1 OR OLD.state='REVOKED'
        OR (NEW.state='APPROVED' AND OLD.state<>'REQUESTED') OR NEW.state NOT IN ('APPROVED','REVOKED') THEN
        RAISE EXCEPTION 'Exact monotonic financial approval required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_approval_control_advance BEFORE UPDATE ON public.gl_financial_approval_control
    FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_control_advance();
CREATE FUNCTION public.gl_financial_approval_complete() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE tenant UUID;book UUID;approval UUID;
BEGIN
    tenant:=NEW.tenant_id;book:=NEW.book_id;
    IF TG_TABLE_NAME='gl_financial_approval_definition' THEN approval:=NEW.id; ELSE approval:=NEW.approval_id; END IF;
    IF NOT public.gl_financial_approval_write_context(tenant,approval,NULL)
        OR NOT public.gl_financial_approval_verify(tenant,book,approval) THEN
        RAISE EXCEPTION 'Complete exact financial approval and audit graph required' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER gl_approval_definition_complete AFTER INSERT ON public.gl_financial_approval_definition
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_complete();
CREATE CONSTRAINT TRIGGER gl_approval_transition_complete AFTER INSERT ON public.gl_financial_approval_transition
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_complete();
CREATE CONSTRAINT TRIGGER gl_approval_control_complete AFTER INSERT OR UPDATE ON public.gl_financial_approval_control
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_financial_approval_complete();

DO $$ DECLARE relation TEXT;BEGIN
    FOREACH relation IN ARRAY ARRAY['gl_financial_approval_definition','gl_financial_approval_transition','gl_financial_approval_control'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC,firefly_audit_writer,firefly_audit_reader',relation);
        EXECUTE format('CREATE POLICY approval_guard_integrity ON public.%I TO firefly_audit_guard USING(true) WITH CHECK(true)',relation);
        EXECUTE format('CREATE POLICY approval_scoped_read ON public.%I FOR SELECT TO firefly_audit_writer USING(public.gl_native_context_matches(tenant_id,book_id,ARRAY[''read'']) OR public.gl_financial_approval_write_context(tenant_id,%I,NULL))',relation,
            CASE WHEN relation='gl_financial_approval_definition' THEN 'id' ELSE 'approval_id' END);
        EXECUTE format('CREATE POLICY approval_scoped_insert ON public.%I FOR INSERT TO firefly_audit_writer WITH CHECK(public.gl_financial_approval_write_context(tenant_id,%I,%I))',relation,
            CASE WHEN relation='gl_financial_approval_definition' THEN 'id' ELSE 'approval_id' END,
            CASE WHEN relation='gl_financial_approval_definition' THEN 'requested_event_id' WHEN relation='gl_financial_approval_transition' THEN 'event_id' ELSE 'last_event_id' END);
        EXECUTE format('GRANT SELECT,INSERT ON public.%I TO firefly_audit_writer',relation);
    END LOOP;
END $$;
CREATE POLICY approval_scoped_advance ON public.gl_financial_approval_control FOR UPDATE TO firefly_audit_writer
    USING(public.gl_financial_approval_write_context(tenant_id,approval_id,NULL))
    WITH CHECK(public.gl_financial_approval_write_context(tenant_id,approval_id,last_event_id));
GRANT UPDATE ON public.gl_financial_approval_control TO firefly_audit_writer;
REVOKE ALL ON FUNCTION public.gl_financial_approval_write_context(UUID,UUID,UUID),
    public.gl_financial_approval_canonical_v1(public.gl_financial_approval_definition,public.gl_financial_approval_transition,firefly_audit.audit_event,public.gl_financial_approval_transition),
    public.gl_financial_approval_verify(UUID,UUID,UUID),public.gl_financial_approval_immutable(),
    public.gl_financial_approval_control_advance(),public.gl_financial_approval_complete() FROM PUBLIC,firefly_audit_writer,firefly_audit_reader;
GRANT EXECUTE ON FUNCTION public.gl_financial_approval_write_context(UUID,UUID,UUID),public.gl_financial_approval_verify(UUID,UUID,UUID) TO firefly_audit_writer;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM firefly_audit_guard;
