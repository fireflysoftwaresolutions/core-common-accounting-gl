-- V1 canonical bytes are stable across Java and PostgreSQL. Validate the original
-- JSON before JSONB can discard duplicate keys or normalize numeric tokens.
CREATE FUNCTION public.gl_native_intent_object_v1(value JSON, fields TEXT[]) RETURNS void
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF json_typeof(value) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM json_object_keys(value))<>cardinality(fields)
        OR EXISTS(SELECT 1 FROM json_object_keys(value) k WHERE NOT k=ANY(fields))
        OR EXISTS(SELECT 1 FROM unnest(fields) k WHERE value->k IS NULL OR json_typeof(value->k)='null') THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: complete closed object required' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION public.gl_native_intent_text_v1(value JSON, maximum INTEGER) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE result TEXT; units INTEGER; whitespace TEXT;
BEGIN
    IF json_typeof(value) IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: exact text required' USING ERRCODE='23514';
    END IF;
    result:=value#>>'{}';
    SELECT coalesce(sum(CASE WHEN ascii(character)>65535 THEN 2 ELSE 1 END),0)::integer INTO units
        FROM regexp_split_to_table(result,'') character;
    whitespace:=chr(9)||chr(10)||chr(11)||chr(12)||chr(13)||chr(28)||chr(29)||chr(30)||chr(31)||chr(32)
        ||chr(5760)||chr(8192)||chr(8193)||chr(8194)||chr(8195)||chr(8196)||chr(8197)||chr(8198)
        ||chr(8200)||chr(8201)||chr(8202)||chr(8232)||chr(8233)||chr(8287)||chr(12288);
    IF units>maximum OR translate(result,whitespace,'')='' THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded nonblank text required' USING ERRCODE='23514';
    END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.gl_native_intent_atom_v1(value JSON, kind TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE result TEXT;
BEGIN
    result:=public.gl_native_intent_text_v1(value,500);
    IF (CASE kind
        WHEN 'uuid' THEN result!~'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR result='00000000-0000-0000-0000-000000000000'
        WHEN 'digest' THEN result!~'^[0-9a-f]{64}$'
        WHEN 'key' THEN result!~'^[A-Za-z0-9._-]{1,64}$'
        WHEN 'iso' THEN result!~'^[A-Z]{3}$'
        WHEN 'encoding' THEN result<>'MASTERS_CURRENCY_POLICY_V1'
        WHEN 'side' THEN result NOT IN ('DR','CR')
        WHEN 'minor' THEN result!~'^[1-9][0-9]{0,37}$'
        WHEN 'date' THEN result!~'^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
            OR result<'1900-01-01' OR result>'9999-12-31' OR to_char(result::date,'YYYY-MM-DD')<>result
        ELSE true END) THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: exact scalar required' USING ERRCODE='23514';
    END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.gl_native_intent_integer_v1(value JSON, minimum BIGINT, maximum BIGINT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE result TEXT;
BEGIN
    result:=value::text;
    IF json_typeof(value) IS DISTINCT FROM 'number' OR result!~'^(0|-?[1-9][0-9]*)$'
        OR length(result)>20 OR result::numeric<minimum OR result::numeric>maximum THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: exact bounded integer token required' USING ERRCODE='23514';
    END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.gl_native_intent_frame_v1(encoding TEXT, fields TEXT[]) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
    SELECT encoding||chr(10)||coalesce(string_agg(octet_length(convert_to(f,'UTF8'))::text||':'||f||chr(10),'' ORDER BY ordinal),'')
    FROM unnest(fields) WITH ORDINALITY AS value(f,ordinal)
$$;

CREATE FUNCTION public.gl_native_request_fields_v1(tenant UUID,book UUID,body TEXT) RETURNS TEXT[]
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE r JSON; s JSON; e JSON; l JSON; c JSON; f TEXT[]; cf TEXT[]; representation TEXT;
    previous_entry TEXT; previous_line TEXT; entry_key TEXT; line_key TEXT; amount TEXT; side TEXT;
    account TEXT; admission TEXT; total_lines INTEGER:=0; entries INTEGER; lines INTEGER;
    entry_totals JSONB; totals JSONB:='{}'; admissions JSONB:='{}'; accounts JSONB:='{}';
    debit NUMERIC; credit NUMERIC; total_value JSONB;
BEGIN
    IF tenant IS NULL OR book IS NULL OR tenant='00000000-0000-0000-0000-000000000000'::uuid
        OR book='00000000-0000-0000-0000-000000000000'::uuid OR body IS NULL
        OR octet_length(body) NOT BETWEEN 1 AND 1048576 OR NOT (body IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded unique JSON object required' USING ERRCODE='23514';
    END IF;
    r:=body::json;
    PERFORM public.gl_native_intent_object_v1(r,ARRAY['actionId','periodId','expectedBookVersion','expectedPeriodVersion',
        'notAfterEpochMillis','businessDate','sourceOccurrenceEpochMillis','source','mappingId','mappingVersion','mappingDigest','description','entries']);
    f:=ARRAY[tenant::text,book::text,
        public.gl_native_intent_atom_v1(r->'actionId','uuid'),public.gl_native_intent_atom_v1(r->'periodId','uuid'),
        public.gl_native_intent_integer_v1(r->'expectedBookVersion',0,9223372036854775807),
        public.gl_native_intent_integer_v1(r->'expectedPeriodVersion',0,9223372036854775807),
        public.gl_native_intent_integer_v1(r->'notAfterEpochMillis',-2208988800000,253402300799999),
        public.gl_native_intent_atom_v1(r->'businessDate','date'),
        public.gl_native_intent_integer_v1(r->'sourceOccurrenceEpochMillis',-2208988800000,253402300799999)];
    s:=r->'source';
    PERFORM public.gl_native_intent_object_v1(s,ARRAY['owner','objectReference','effectReference','revision','digest','eventKind',
        'effectOrdinal','financialReceiptReference','provenanceReference','observedAtEpochMillis']);
    f:=f||ARRAY[public.gl_native_intent_text_v1(s->'owner',100),public.gl_native_intent_text_v1(s->'objectReference',255),
        public.gl_native_intent_text_v1(s->'effectReference',255),public.gl_native_intent_integer_v1(s->'revision',1,9223372036854775807),
        public.gl_native_intent_atom_v1(s->'digest','digest'),public.gl_native_intent_text_v1(s->'eventKind',100),
        public.gl_native_intent_integer_v1(s->'effectOrdinal',0,2147483647),public.gl_native_intent_text_v1(s->'financialReceiptReference',500),
        public.gl_native_intent_text_v1(s->'provenanceReference',500),
        public.gl_native_intent_integer_v1(s->'observedAtEpochMillis',-2208988800000,253402300799999),
        public.gl_native_intent_atom_v1(r->'mappingId','uuid'),public.gl_native_intent_integer_v1(r->'mappingVersion',1,9223372036854775807),
        public.gl_native_intent_atom_v1(r->'mappingDigest','digest'),public.gl_native_intent_text_v1(r->'description',500)];
    IF json_typeof(r->'entries') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded entries required' USING ERRCODE='23514';
    END IF;
    entries:=json_array_length(r->'entries');
    IF entries NOT BETWEEN 1 AND 50 THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded entries required' USING ERRCODE='23514';
    END IF;
    f:=array_append(f,entries::text);
    FOR e IN SELECT value FROM json_array_elements(r->'entries') LOOP
        PERFORM public.gl_native_intent_object_v1(e,ARRAY['entryKey','description','lines']);
        entry_key:=public.gl_native_intent_atom_v1(e->'entryKey','key');
        IF previous_entry IS NOT NULL AND previous_entry COLLATE "C">=entry_key COLLATE "C" THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: strict entry order required' USING ERRCODE='23514';
        END IF;
        previous_entry:=entry_key;
        IF json_typeof(e->'lines') IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded lines required' USING ERRCODE='23514';
        END IF;
        lines:=json_array_length(e->'lines');total_lines:=total_lines+lines;
        IF lines<2 OR total_lines>500 THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded lines required' USING ERRCODE='23514';
        END IF;
        f:=f||ARRAY[entry_key,public.gl_native_intent_text_v1(e->'description',500),lines::text];
        previous_line:=NULL;entry_totals:='{}';
        FOR l IN SELECT value FROM json_array_elements(e->'lines') LOOP
            PERFORM public.gl_native_intent_object_v1(l,ARRAY['lineKey','accountId','expectedAccountVersion','currencyAdmissionId',
                'expectedCurrencyVersion','currency','postedScale','side','amountMinor','memo']);
            line_key:=public.gl_native_intent_atom_v1(l->'lineKey','key');
            IF previous_line IS NOT NULL AND previous_line COLLATE "C">=line_key COLLATE "C" THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: strict line order required' USING ERRCODE='23514';
            END IF;
            previous_line:=line_key;
            account:=public.gl_native_intent_atom_v1(l->'accountId','uuid');
            admission:=public.gl_native_intent_atom_v1(l->'currencyAdmissionId','uuid');
            f:=f||ARRAY[line_key,account,public.gl_native_intent_integer_v1(l->'expectedAccountVersion',0,9223372036854775807),
                admission,public.gl_native_intent_integer_v1(l->'expectedCurrencyVersion',0,9223372036854775807)];
            c:=l->'currency';
            PERFORM public.gl_native_intent_object_v1(c,ARRAY['policyId','currencyId','isoCode','version','digest','encodingVersion']);
            cf:=ARRAY[public.gl_native_intent_atom_v1(c->'policyId','uuid'),public.gl_native_intent_atom_v1(c->'currencyId','uuid'),
                public.gl_native_intent_atom_v1(c->'isoCode','iso'),public.gl_native_intent_integer_v1(c->'version',1,9223372036854775807),
                public.gl_native_intent_atom_v1(c->'digest','digest'),public.gl_native_intent_atom_v1(c->'encodingVersion','encoding')];
            representation:=public.gl_native_intent_frame_v1('GL_CURRENCY_REPRESENTATION_V1',
                cf||public.gl_native_intent_integer_v1(l->'postedScale',0,10));
            IF (admissions ? admission AND admissions->>admission<>representation)
                OR (accounts ? account AND accounts->>account<>representation) THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: consistent account and admission representation required' USING ERRCODE='23514';
            END IF;
            admissions:=jsonb_set(admissions,ARRAY[admission],to_jsonb(representation));
            accounts:=jsonb_set(accounts,ARRAY[account],to_jsonb(representation));
            side:=public.gl_native_intent_atom_v1(l->'side','side');amount:=public.gl_native_intent_atom_v1(l->'amountMinor','minor');
            f:=f||cf||ARRAY[public.gl_native_intent_integer_v1(l->'postedScale',0,10),side,amount,public.gl_native_intent_text_v1(l->'memo',500)];
            entry_totals:=jsonb_set(entry_totals,ARRAY[representation],coalesce(entry_totals->representation,'{}'));
            totals:=jsonb_set(totals,ARRAY[representation],coalesce(totals->representation,'{}'));
            entry_totals:=jsonb_set(entry_totals,ARRAY[representation,side],to_jsonb(coalesce((entry_totals#>>ARRAY[representation,side])::numeric,0)+amount::numeric));
            totals:=jsonb_set(totals,ARRAY[representation,side],to_jsonb(coalesce((totals#>>ARRAY[representation,side])::numeric,0)+amount::numeric));
            IF (SELECT count(*) FROM jsonb_object_keys(totals))>32 THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded representations required' USING ERRCODE='23514';
            END IF;
        END LOOP;
        FOR total_value IN SELECT value FROM jsonb_each(entry_totals) LOOP
            debit:=coalesce((total_value->>'DR')::numeric,0);credit:=coalesce((total_value->>'CR')::numeric,0);
            IF debit<>credit OR debit<=0 OR debit>=1e38 THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded entry balance required' USING ERRCODE='23514';
            END IF;
        END LOOP;
    END LOOP;
    FOR total_value IN SELECT value FROM jsonb_each(totals) LOOP
        debit:=coalesce((total_value->>'DR')::numeric,0);credit:=coalesce((total_value->>'CR')::numeric,0);
        IF debit<>credit OR debit<=0 OR debit>=1e38 THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: bounded posting balance required' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN f;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'GL_NATIVE_INTENT: exact bounded complete posting request required' USING ERRCODE='23514';
END $$;

CREATE FUNCTION public.gl_native_request_canonical_v1(tenant UUID,book UUID,body TEXT,economic BOOLEAN) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE f TEXT[];
BEGIN
    IF economic IS NULL THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: explicit encoding required' USING ERRCODE='23514';
    END IF;
    f:=public.gl_native_request_fields_v1(tenant,book,body);
    IF economic THEN
        RETURN public.gl_native_intent_frame_v1('GL_POSTING_ECONOMIC_INTENT_V1',f[1:2]||f[4:cardinality(f)]);
    END IF;
    RETURN public.gl_native_intent_frame_v1('GL_POSTING_INTENT_V1',f);
END $$;

CREATE FUNCTION public.gl_native_request_source_key_v1(tenant UUID,book UUID,body TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE f TEXT[];
BEGIN
    f:=public.gl_native_request_fields_v1(tenant,book,body);
    RETURN encode(sha256(convert_to(public.gl_native_intent_frame_v1('GL_SOURCE_EFFECT_IDENTITY_V1',
        ARRAY[f[1],f[2],f[10],f[11],f[12],f[13],f[15],f[16]]),'UTF8')),'hex');
END $$;

-- Existing immutable foundation actions do not contain a complete approved
-- request. Preserve that fact; new application writes cannot choose this shape.
ALTER TABLE public.gl_native_journal_action
    ADD COLUMN request_shape TEXT NOT NULL DEFAULT 'GL_FOUNDATION_V1',
    ADD COLUMN original_request TEXT,
    ADD COLUMN request_document JSONB GENERATED ALWAYS AS
        (CASE WHEN request_shape='GL_POSTING_REQUEST_V1' THEN original_request::jsonb END) STORED;
ALTER TABLE public.gl_native_journal_action ALTER COLUMN request_shape SET DEFAULT 'GL_POSTING_REQUEST_V1';
ALTER TABLE public.gl_native_journal_action ADD CONSTRAINT gl_action_request_shape CHECK (
    (request_shape='GL_FOUNDATION_V1' AND original_request IS NULL)
    OR (request_shape='GL_POSTING_REQUEST_V1' AND original_request IS NOT NULL
        AND octet_length(original_request) BETWEEN 1 AND 1048576));

CREATE FUNCTION public.gl_native_action_intent_guard_v1() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE f TEXT[]; accounts UUID[];
BEGIN
    IF NEW.request_shape IS DISTINCT FROM 'GL_POSTING_REQUEST_V1' OR NEW.kind NOT IN ('POST','ABSENT_CLOSED') THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: complete qualified request shape required' USING ERRCODE='23514';
    END IF;
    -- Only this raw-text validator establishes complete canonical authority.
    -- The generated JSONB is a lookup representation, never a validation source.
    f:=public.gl_native_request_fields_v1(NEW.tenant_id,NEW.book_id,NEW.original_request);
    SELECT array_agg(DISTINCT (l->>'accountId')::uuid ORDER BY (l->>'accountId')::uuid) INTO accounts
        FROM json_array_elements(NEW.original_request::json->'entries') e
        CROSS JOIN LATERAL json_array_elements(e->'lines') l;
    IF NEW.action_id::text IS DISTINCT FROM f[3] OR NEW.not_after_epoch_millis::text IS DISTINCT FROM f[7]
        OR NEW.account_ids IS DISTINCT FROM accounts
        OR NEW.canonical_intent IS DISTINCT FROM public.gl_native_intent_frame_v1('GL_POSTING_INTENT_V1',f)
        OR NEW.canonical_economic_intent IS DISTINCT FROM public.gl_native_intent_frame_v1('GL_POSTING_ECONOMIC_INTENT_V1',f[1:2]||f[4:cardinality(f)])
        OR NEW.source_claim_key IS DISTINCT FROM encode(sha256(convert_to(public.gl_native_intent_frame_v1('GL_SOURCE_EFFECT_IDENTITY_V1',
            ARRAY[f[1],f[2],f[10],f[11],f[12],f[13],f[15],f[16]]),'UTF8')),'hex') THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: exact retained original intent required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_action_intent_guard_v1 BEFORE INSERT ON public.gl_native_journal_action
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_action_intent_guard_v1();

CREATE FUNCTION public.gl_native_intent_rows_complete_v1() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE action public.gl_native_journal_action%ROWTYPE; r JSONB; e JSONB; l JSONB;
    currency public.gl_native_currency_admission%ROWTYPE; expected_entries BIGINT; expected_lines BIGINT;
    book public.gl_native_book%ROWTYPE; period public.gl_native_period%ROWTYPE;
    account public.gl_native_account%ROWTYPE; gate UUID; now_millis BIGINT;
BEGIN
    -- This owned nondeferred trigger defines admission, not the earlier INSERT
    -- attempt or deferred completeness. Its financial locks live until TX end.
    IF TG_NAME='gl_action_admission_v1' THEN
        IF TG_OP<>'INSERT' OR TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME<>'gl_native_journal_action'
            OR NEW.request_shape IS DISTINCT FROM 'GL_POSTING_REQUEST_V1' THEN
            RAISE EXCEPTION 'GL_NATIVE_ADMISSION: qualified action insertion required' USING ERRCODE='23514';
        END IF;
        action:=NEW;
        r:=action.request_document;
        IF NOT coalesce(public.gl_native_context_matches(action.tenant_id,action.book_id,
                ARRAY[CASE action.kind WHEN 'POST' THEN 'post' WHEN 'ABSENT_CLOSED' THEN 'reconcile' END]),false)
            OR action.owner_xid IS DISTINCT FROM pg_catalog.pg_current_xact_id()
            OR action.action_id::text IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.action',true)
            OR action.intent_digest IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.intent',true)
            OR action.actor_issuer IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.actor_issuer',true)
            OR action.actor_subject IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.actor_subject',true)
            OR pg_catalog.to_jsonb(action.account_ids) IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.accounts',true)::jsonb
            OR action.authority_until_epoch_millis>pg_catalog.current_setting('firefly.gl.authority_until',true)::bigint THEN
            RAISE EXCEPTION 'GL_NATIVE_ADMISSION: exact current native action authority required' USING ERRCODE='23514';
        END IF;
        IF action.kind='POST' THEN
            SELECT * INTO book FROM public.gl_native_book
                WHERE tenant_id=action.tenant_id AND id=action.book_id FOR NO KEY UPDATE;
            SELECT * INTO period FROM public.gl_native_period
                WHERE tenant_id=action.tenant_id AND book_id=action.book_id AND id=(r->>'periodId')::uuid
                FOR NO KEY UPDATE;
            IF book.lifecycle IS DISTINCT FROM 'ACTIVE' OR period.lifecycle IS DISTINCT FROM 'OPEN'
                OR book.control_version IS DISTINCT FROM (r->>'expectedBookVersion')::bigint
                OR period.control_version IS DISTINCT FROM (r->>'expectedPeriodVersion')::bigint
                OR (r->>'businessDate')::date<period.from_date OR (r->>'businessDate')::date>=period.to_date THEN
                RAISE EXCEPTION 'GL_NATIVE_ADMISSION: active exact book and open period required' USING ERRCODE='23514';
            END IF;
            FOR gate IN SELECT DISTINCT value FROM pg_catalog.unnest(action.account_ids) value ORDER BY value LOOP
                SELECT * INTO account FROM public.gl_native_account
                    WHERE tenant_id=action.tenant_id AND book_id=action.book_id AND account_id=gate FOR NO KEY UPDATE;
                IF account.account_id IS NULL OR account.kind IS DISTINCT FROM 'POSTING' OR account.lifecycle IS DISTINCT FROM 'ACTIVE'
                    OR EXISTS(SELECT 1 FROM pg_catalog.jsonb_array_elements(r->'entries') intent_entry
                        CROSS JOIN LATERAL pg_catalog.jsonb_array_elements(intent_entry->'lines') intent_line
                        WHERE (intent_line->>'accountId')::uuid=gate
                            AND (account.control_version IS DISTINCT FROM (intent_line->>'expectedAccountVersion')::bigint
                                OR account.currency_admission_id IS DISTINCT FROM (intent_line->>'currencyAdmissionId')::uuid)) THEN
                    RAISE EXCEPTION 'GL_NATIVE_ADMISSION: active exact posting account required' USING ERRCODE='23514';
                END IF;
            END LOOP;
            FOR gate IN SELECT DISTINCT (intent_line->>'currencyAdmissionId')::uuid
                FROM pg_catalog.jsonb_array_elements(r->'entries') intent_entry
                CROSS JOIN LATERAL pg_catalog.jsonb_array_elements(intent_entry->'lines') intent_line ORDER BY 1 LOOP
                SELECT * INTO currency FROM public.gl_native_currency_admission
                    WHERE tenant_id=action.tenant_id AND book_id=action.book_id AND id=gate FOR NO KEY UPDATE;
                IF currency.id IS NULL OR currency.lifecycle IS DISTINCT FROM 'ACTIVE'
                    OR EXISTS(SELECT 1 FROM pg_catalog.jsonb_array_elements(r->'entries') intent_entry
                        CROSS JOIN LATERAL pg_catalog.jsonb_array_elements(intent_entry->'lines') intent_line
                        WHERE (intent_line->>'currencyAdmissionId')::uuid=gate
                            AND (currency.control_version IS DISTINCT FROM (intent_line->>'expectedCurrencyVersion')::bigint
                                OR currency.posted_scale IS DISTINCT FROM (intent_line->>'postedScale')::integer
                                OR intent_line->'currency' IS DISTINCT FROM pg_catalog.jsonb_build_object(
                                    'policyId',currency.policy_id::text,'currencyId',currency.currency_id::text,
                                    'isoCode',currency.iso_code,'version',currency.policy_version,
                                    'digest',currency.policy_digest,'encodingVersion',currency.policy_encoding))) THEN
                    RAISE EXCEPTION 'GL_NATIVE_ADMISSION: active exact retained currency required' USING ERRCODE='23514';
                END IF;
            END LOOP;
        END IF;
        -- Recheck finite authority after waits. The request cutoff applies at
        -- this admission boundary, not again when admitted work later commits.
        IF NOT coalesce(public.gl_native_context_matches(action.tenant_id,action.book_id,
                ARRAY[CASE action.kind WHEN 'POST' THEN 'post' WHEN 'ABSENT_CLOSED' THEN 'reconcile' END]),false)
            OR action.owner_xid IS DISTINCT FROM pg_catalog.pg_current_xact_id()
            OR action.action_id::text IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.action',true)
            OR action.intent_digest IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.intent',true)
            OR action.actor_issuer IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.actor_issuer',true)
            OR action.actor_subject IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.actor_subject',true)
            OR pg_catalog.to_jsonb(action.account_ids) IS DISTINCT FROM pg_catalog.current_setting('firefly.gl.accounts',true)::jsonb
            OR action.authority_until_epoch_millis>pg_catalog.current_setting('firefly.gl.authority_until',true)::bigint THEN
            RAISE EXCEPTION 'GL_NATIVE_ADMISSION: exact current native action authority required' USING ERRCODE='23514';
        END IF;
        now_millis:=floor(extract(epoch FROM pg_catalog.clock_timestamp())*1000)::bigint;
        IF action.authority_until_epoch_millis<=now_millis
            OR (action.kind='POST' AND action.not_after_epoch_millis<=now_millis)
            OR (action.kind='ABSENT_CLOSED' AND action.not_after_epoch_millis>now_millis) THEN
            RAISE EXCEPTION 'GL_NATIVE_ADMISSION: current final database admission deadline required' USING ERRCODE='23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO action FROM public.gl_native_journal_action WHERE action_id=NEW.action_id;
    IF NOT FOUND OR action.owner_xid<>pg_current_xact_id()
        OR action.request_shape IS DISTINCT FROM 'GL_POSTING_REQUEST_V1' THEN
        RAISE EXCEPTION 'GL_NATIVE_INTENT: same-transaction complete original request required' USING ERRCODE='23514';
    END IF;
    r:=action.request_document;
    IF TG_TABLE_NAME='gl_native_journal_action' THEN
        SELECT count(*),sum(jsonb_array_length(value->'lines')) INTO expected_entries,expected_lines
            FROM jsonb_array_elements(r->'entries');
        IF action.kind='ABSENT_CLOSED' THEN
            IF EXISTS(SELECT 1 FROM public.gl_native_posting WHERE action_id=action.action_id)
                OR EXISTS(SELECT 1 FROM public.gl_native_entry WHERE action_id=action.action_id)
                OR EXISTS(SELECT 1 FROM public.gl_native_line WHERE action_id=action.action_id) THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: closed intent has economic rows' USING ERRCODE='23514';
            END IF;
        ELSIF (SELECT count(*) FROM public.gl_native_entry WHERE action_id=action.action_id)<>expected_entries
            OR (SELECT count(*) FROM public.gl_native_line WHERE action_id=action.action_id)<>expected_lines THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: complete original row cardinality required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='gl_native_posting' THEN
        IF NEW.kind<>'POST' OR NEW.original_posting_id IS NOT NULL OR NEW.id<>action.posting_id
            OR NEW.tenant_id<>action.tenant_id OR NEW.book_id<>action.book_id
            OR NEW.period_id::text IS DISTINCT FROM r->>'periodId'
            OR NEW.expected_book_version::text IS DISTINCT FROM r->>'expectedBookVersion'
            OR NEW.expected_period_version::text IS DISTINCT FROM r->>'expectedPeriodVersion'
            OR NEW.business_date IS DISTINCT FROM (r->>'businessDate')::date
            OR NEW.mapping_id::text IS DISTINCT FROM r->>'mappingId'
            OR NEW.mapping_version::text IS DISTINCT FROM r->>'mappingVersion'
            OR NEW.mapping_digest IS DISTINCT FROM r->>'mappingDigest' THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: original posting header required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME IN ('gl_native_entry','gl_native_line') THEN
        IF NEW.tenant_id<>action.tenant_id OR NEW.book_id<>action.book_id OR NEW.posting_id<>action.posting_id THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: exact economic parent required' USING ERRCODE='23514';
        END IF;
        SELECT value INTO e FROM jsonb_array_elements(r->'entries') WHERE value->>'entryKey'=NEW.entry_key;
        IF e IS NULL THEN
            RAISE EXCEPTION 'GL_NATIVE_INTENT: approved entry membership required' USING ERRCODE='23514';
        END IF;
        IF TG_TABLE_NAME='gl_native_entry' THEN
            IF NEW.description IS DISTINCT FROM e->>'description' THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: original entry description required' USING ERRCODE='23514';
            END IF;
            IF (SELECT count(*) FROM public.gl_native_line WHERE action_id=action.action_id AND entry_key=NEW.entry_key)
                <>jsonb_array_length(e->'lines') THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: original entry line cardinality required' USING ERRCODE='23514';
            END IF;
        ELSE
            SELECT value INTO l FROM jsonb_array_elements(e->'lines') WHERE value->>'lineKey'=NEW.line_key;
            SELECT * INTO currency FROM public.gl_native_currency_admission
                WHERE tenant_id=action.tenant_id AND book_id=action.book_id AND id=NEW.currency_admission_id;
            IF l IS NULL OR currency.id IS NULL
                OR NEW.account_id::text IS DISTINCT FROM l->>'accountId'
                OR NEW.expected_account_version::text IS DISTINCT FROM l->>'expectedAccountVersion'
                OR NEW.currency_admission_id::text IS DISTINCT FROM l->>'currencyAdmissionId'
                OR NEW.expected_currency_version::text IS DISTINCT FROM l->>'expectedCurrencyVersion'
                OR NEW.posted_scale::text IS DISTINCT FROM l->>'postedScale'
                OR NEW.side IS DISTINCT FROM l->>'side' OR NEW.amount_minor IS DISTINCT FROM l->>'amountMinor'
                OR NEW.memo IS DISTINCT FROM l->>'memo'
                OR l->'currency' IS DISTINCT FROM jsonb_build_object('policyId',currency.policy_id::text,
                    'currencyId',currency.currency_id::text,'isoCode',currency.iso_code,'version',currency.policy_version,
                    'digest',currency.policy_digest,'encodingVersion',currency.policy_encoding)
                OR NEW.policy_digest IS DISTINCT FROM currency.policy_digest THEN
                RAISE EXCEPTION 'GL_NATIVE_INTENT: exact original line and currency representation required' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER gl_action_admission_v1 AFTER INSERT ON public.gl_native_journal_action
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_intent_rows_complete_v1();

CREATE CONSTRAINT TRIGGER gl_action_intent_complete_v1 AFTER INSERT ON public.gl_native_journal_action
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_intent_rows_complete_v1();
CREATE CONSTRAINT TRIGGER gl_posting_intent_complete_v1 AFTER INSERT ON public.gl_native_posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_intent_rows_complete_v1();
CREATE CONSTRAINT TRIGGER gl_entry_intent_complete_v1 AFTER INSERT ON public.gl_native_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_intent_rows_complete_v1();
CREATE CONSTRAINT TRIGGER gl_line_intent_complete_v1 AFTER INSERT ON public.gl_native_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_intent_rows_complete_v1();
CREATE CONSTRAINT TRIGGER gl_event_intent_complete_v1 AFTER INSERT ON public.gl_native_journal_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_intent_rows_complete_v1();

GRANT CREATE ON SCHEMA public TO gl_native_guard;
ALTER FUNCTION public.gl_native_intent_rows_complete_v1() OWNER TO gl_native_guard;
REVOKE CREATE ON SCHEMA public FROM gl_native_guard;
REVOKE ALL ON FUNCTION public.gl_native_intent_rows_complete_v1() FROM PUBLIC;
