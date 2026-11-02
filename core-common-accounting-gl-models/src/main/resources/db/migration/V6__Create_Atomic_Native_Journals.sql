CREATE TABLE public.gl_native_book_sequence (
    tenant_id UUID NOT NULL,
    book_id UUID PRIMARY KEY,
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 0),
    FOREIGN KEY (tenant_id,book_id) REFERENCES public.gl_native_book(tenant_id,id)
);

CREATE TABLE public.gl_native_journal_action (
    action_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('POST','REVERSE','ABSENT_CLOSED')),
    intent_digest TEXT NOT NULL CHECK (intent_digest ~ '^[0-9a-f]{64}$'),
    canonical_intent TEXT NOT NULL,
    economic_digest TEXT NOT NULL CHECK (economic_digest ~ '^[0-9a-f]{64}$'),
    canonical_economic_intent TEXT NOT NULL,
    source_claim_key TEXT NOT NULL CHECK (source_claim_key ~ '^[0-9a-f]{64}$'),
    account_ids UUID[] NOT NULL CHECK (array_ndims(account_ids)=1 AND cardinality(account_ids) BETWEEN 1 AND 500
        AND array_position(account_ids,NULL) IS NULL
        AND NOT ('00000000-0000-0000-0000-000000000000'::uuid=ANY(account_ids))),
    not_after_epoch_millis BIGINT NOT NULL CHECK (not_after_epoch_millis BETWEEN -2208988800000 AND 253402300799999),
    receipt_id UUID NOT NULL UNIQUE,
    actor_issuer TEXT NOT NULL CHECK (btrim(actor_issuer) <> ''),
    actor_subject TEXT NOT NULL CHECK (btrim(actor_subject) <> ''),
    authority_until_epoch_millis BIGINT NOT NULL CHECK (authority_until_epoch_millis BETWEEN -2208988800000 AND 253402300799999),
    posting_id UUID UNIQUE,
    owner_xid XID8 NOT NULL DEFAULT pg_current_xact_id(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,book_id,action_id),
    UNIQUE (tenant_id,book_id,action_id,kind),
    UNIQUE (tenant_id,book_id,source_claim_key),
    CHECK (octet_length(canonical_intent) BETWEEN 1 AND 1048576),
    CHECK (octet_length(canonical_economic_intent) BETWEEN 1 AND 1048576),
    CHECK (intent_digest=encode(sha256(convert_to(canonical_intent,'UTF8')),'hex')),
    CHECK (economic_digest=encode(sha256(convert_to(canonical_economic_intent,'UTF8')),'hex')),
    CHECK ((kind='ABSENT_CLOSED' AND posting_id IS NULL)
        OR (kind<>'ABSENT_CLOSED' AND posting_id IS NOT NULL)),
    FOREIGN KEY (tenant_id,book_id) REFERENCES public.gl_native_book(tenant_id,id)
);

ALTER TABLE public.gl_native_mapping ADD CONSTRAINT gl_mapping_exact_revision
    UNIQUE (tenant_id,book_id,id,revision,definition_digest);
ALTER TABLE public.gl_native_account ADD CONSTRAINT gl_account_exact_currency
    UNIQUE (tenant_id,book_id,account_id,currency_admission_id);
ALTER TABLE public.gl_native_currency_admission ADD CONSTRAINT gl_currency_exact_representation
    UNIQUE (tenant_id,book_id,id,policy_digest,posted_scale);

CREATE TABLE public.gl_native_posting (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    action_id UUID NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK (kind IN ('POST','REVERSE')),
    journal_sequence BIGINT NOT NULL CHECK (journal_sequence > 0),
    expected_book_version BIGINT NOT NULL CHECK (expected_book_version >= 0),
    period_id UUID NOT NULL,
    expected_period_version BIGINT NOT NULL CHECK (expected_period_version >= 0),
    business_date DATE NOT NULL CHECK (business_date BETWEEN DATE '1900-01-01' AND DATE '9999-12-31'),
    mapping_id UUID NOT NULL,
    mapping_version BIGINT NOT NULL CHECK (mapping_version > 0),
    mapping_digest TEXT NOT NULL CHECK (mapping_digest ~ '^[0-9a-f]{64}$'),
    original_posting_id UUID UNIQUE,
    UNIQUE (tenant_id,book_id,id),
    UNIQUE (tenant_id,book_id,id,action_id),
    UNIQUE (tenant_id,book_id,journal_sequence),
    CHECK ((kind='POST' AND original_posting_id IS NULL)
        OR (kind='REVERSE' AND original_posting_id IS NOT NULL AND original_posting_id<>id)),
    FOREIGN KEY (tenant_id,book_id,action_id,kind)
        REFERENCES public.gl_native_journal_action(tenant_id,book_id,action_id,kind) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,book_id,period_id) REFERENCES public.gl_native_period(tenant_id,book_id,id),
    FOREIGN KEY (tenant_id,book_id,mapping_id,mapping_version,mapping_digest)
        REFERENCES public.gl_native_mapping(tenant_id,book_id,id,revision,definition_digest),
    FOREIGN KEY (tenant_id,book_id,original_posting_id)
        REFERENCES public.gl_native_posting(tenant_id,book_id,id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE public.gl_native_entry (
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    action_id UUID NOT NULL,
    posting_id UUID NOT NULL,
    entry_key TEXT NOT NULL CHECK (entry_key ~ '^[A-Za-z0-9._-]{1,64}$'),
    description TEXT NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
    PRIMARY KEY (posting_id,entry_key),
    UNIQUE (tenant_id,book_id,posting_id,entry_key,action_id),
    FOREIGN KEY (tenant_id,book_id,posting_id,action_id)
        REFERENCES public.gl_native_posting(tenant_id,book_id,id,action_id) DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE public.gl_native_journal_action ADD CONSTRAINT gl_action_exact_posting
    FOREIGN KEY (tenant_id,book_id,posting_id,action_id)
    REFERENCES public.gl_native_posting(tenant_id,book_id,id,action_id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.gl_native_line (
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    action_id UUID NOT NULL,
    posting_id UUID NOT NULL,
    entry_key TEXT NOT NULL,
    line_key TEXT NOT NULL CHECK (line_key ~ '^[A-Za-z0-9._-]{1,64}$'),
    account_id UUID NOT NULL,
    expected_account_version BIGINT NOT NULL CHECK (expected_account_version >= 0),
    currency_admission_id UUID NOT NULL,
    expected_currency_version BIGINT NOT NULL CHECK (expected_currency_version >= 0),
    posted_scale INTEGER NOT NULL CHECK (posted_scale BETWEEN 0 AND 10),
    policy_digest TEXT NOT NULL CHECK (policy_digest ~ '^[0-9a-f]{64}$'),
    side TEXT NOT NULL CHECK (side IN ('DR','CR')),
    -- Preserve exact input digits as well as an indexed/arithmetic NUMERIC.
    -- A NUMERIC(38,0)-only column silently rounds raw SQL fractional input.
    amount_minor TEXT NOT NULL CHECK (amount_minor ~ '^[1-9][0-9]{0,37}$'),
    amount_value NUMERIC(38,0) GENERATED ALWAYS AS (amount_minor::numeric) STORED,
    memo TEXT NOT NULL CHECK (length(memo) BETWEEN 1 AND 500),
    PRIMARY KEY (posting_id,entry_key,line_key),
    FOREIGN KEY (tenant_id,book_id,posting_id,entry_key,action_id)
        REFERENCES public.gl_native_entry(tenant_id,book_id,posting_id,entry_key,action_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,book_id,account_id,currency_admission_id)
        REFERENCES public.gl_native_account(tenant_id,book_id,account_id,currency_admission_id),
    FOREIGN KEY (tenant_id,book_id,currency_admission_id,policy_digest,posted_scale)
        REFERENCES public.gl_native_currency_admission(tenant_id,book_id,id,policy_digest,posted_scale)
);
CREATE INDEX gl_native_line_account_sequence ON public.gl_native_line(tenant_id,book_id,account_id,posting_id);

CREATE TABLE public.gl_native_journal_event (
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    action_id UUID NOT NULL,
    channel TEXT NOT NULL CHECK (channel IN ('AUDIT','OUTBOX')),
    payload_encoding TEXT NOT NULL CHECK (payload_encoding='GL_ACCOUNTING_EVENT_V1'),
    payload TEXT NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 1048576),
    payload_digest TEXT NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (action_id,channel),
    CHECK (jsonb_typeof(payload::jsonb)='object'),
    CHECK (payload_digest=encode(sha256(convert_to(payload,'UTF8')),'hex')),
    FOREIGN KEY (tenant_id,book_id,action_id)
        REFERENCES public.gl_native_journal_action(tenant_id,book_id,action_id) DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE public.gl_native_book_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_book_sequence FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_journal_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_journal_action FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_posting ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_posting FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_entry FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_line FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_journal_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_journal_event FORCE ROW LEVEL SECURITY;

GRANT SELECT ON public.gl_native_book_sequence,public.gl_native_journal_action,
    public.gl_native_posting,public.gl_native_entry,public.gl_native_line,
    public.gl_native_journal_event TO gl_native_guard;
GRANT UPDATE ON public.gl_native_book_sequence,public.gl_native_account,
    public.gl_native_currency_admission,public.gl_native_period TO gl_native_guard;
CREATE POLICY gl_guard_sequence ON public.gl_native_book_sequence FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_action ON public.gl_native_journal_action FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_posting ON public.gl_native_posting FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_entry ON public.gl_native_entry FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_line ON public.gl_native_line FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_event ON public.gl_native_journal_event FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_sequence_lock ON public.gl_native_book_sequence FOR UPDATE TO gl_native_guard USING (true) WITH CHECK (false);
CREATE POLICY gl_guard_account_lock ON public.gl_native_account FOR UPDATE TO gl_native_guard USING (true) WITH CHECK (false);
CREATE POLICY gl_guard_currency_lock ON public.gl_native_currency_admission FOR UPDATE TO gl_native_guard USING (true) WITH CHECK (false);
CREATE POLICY gl_guard_period_lock ON public.gl_native_period FOR UPDATE TO gl_native_guard USING (true) WITH CHECK (false);

CREATE FUNCTION public.gl_native_journal_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'GL_NATIVE_IMMUTABLE: financial history is append-only' USING ERRCODE='23514';
END $$;
CREATE TRIGGER gl_action_immutable BEFORE UPDATE OR DELETE ON public.gl_native_journal_action FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_immutable();
CREATE TRIGGER gl_posting_immutable BEFORE UPDATE OR DELETE ON public.gl_native_posting FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_immutable();
CREATE TRIGGER gl_entry_immutable BEFORE UPDATE OR DELETE ON public.gl_native_entry FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_immutable();
CREATE TRIGGER gl_line_immutable BEFORE UPDATE OR DELETE ON public.gl_native_line FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_immutable();
CREATE TRIGGER gl_event_immutable BEFORE UPDATE OR DELETE ON public.gl_native_journal_event FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_immutable();

CREATE FUNCTION public.gl_native_action_insert_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE now_millis BIGINT := floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
BEGIN
    IF NEW.authority_until_epoch_millis<=now_millis
        OR (NEW.kind='ABSENT_CLOSED' AND NEW.not_after_epoch_millis>now_millis)
        OR (NEW.kind<>'ABSENT_CLOSED' AND NEW.not_after_epoch_millis<=now_millis) THEN
        RAISE EXCEPTION 'GL_NATIVE_ACTION: current finite admission deadline required' USING ERRCODE='23514';
    END IF;
    NEW.owner_xid := pg_current_xact_id();
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER gl_action_insert_guard BEFORE INSERT ON public.gl_native_journal_action FOR EACH ROW EXECUTE FUNCTION public.gl_native_action_insert_guard();

CREATE FUNCTION public.gl_native_sequence_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF TG_OP='DELETE' OR (TG_OP='INSERT' AND NEW.last_sequence<>0) THEN
        RAISE EXCEPTION 'GL_NATIVE_SEQUENCE: retained zero origin required' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.tenant_id<>OLD.tenant_id OR NEW.book_id<>OLD.book_id
        OR OLD.last_sequence=9223372036854775807 OR NEW.last_sequence<>OLD.last_sequence+1) THEN
        RAISE EXCEPTION 'GL_NATIVE_SEQUENCE: exact checked next sequence required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_sequence_guard BEFORE INSERT OR UPDATE OR DELETE ON public.gl_native_book_sequence FOR EACH ROW EXECUTE FUNCTION public.gl_native_sequence_guard();

CREATE FUNCTION public.gl_native_sequence_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE current_sequence BIGINT; actual BIGINT;
BEGIN
    SELECT last_sequence INTO current_sequence FROM public.gl_native_book_sequence WHERE book_id=NEW.book_id;
    SELECT coalesce(max(journal_sequence),0) INTO actual FROM public.gl_native_posting WHERE book_id=NEW.book_id;
    IF current_sequence IS NULL OR current_sequence<>actual THEN
        RAISE EXCEPTION 'GL_NATIVE_SEQUENCE: counter and complete journal disagree' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER gl_sequence_complete AFTER INSERT OR UPDATE ON public.gl_native_book_sequence
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_sequence_complete();

CREATE FUNCTION public.gl_native_journal_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE action public.gl_native_journal_action%ROWTYPE;
    posting public.gl_native_posting%ROWTYPE;
    book public.gl_native_book%ROWTYPE;
    period public.gl_native_period%ROWTYPE;
    account public.gl_native_account%ROWTYPE;
    currency public.gl_native_currency_admission%ROWTYPE;
    original public.gl_native_posting%ROWTYPE;
    entry_count BIGINT; line_count BIGINT; gate UUID; sequence BIGINT;
BEGIN
    SELECT * INTO action FROM public.gl_native_journal_action WHERE action_id=NEW.action_id;
    IF NOT FOUND OR action.owner_xid<>pg_current_xact_id() THEN
        RAISE EXCEPTION 'GL_NATIVE_COMPLETE: same-transaction immutable action required' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM public.gl_native_journal_event WHERE action_id=action.action_id)<>2
        OR EXISTS(SELECT 1 FROM public.gl_native_journal_event e WHERE e.action_id=action.action_id
            AND (e.payload::jsonb->>'actionId' IS DISTINCT FROM action.action_id::text
            OR e.payload::jsonb->>'receiptId' IS DISTINCT FROM action.receipt_id::text
            OR e.payload::jsonb->>'intentDigest' IS DISTINCT FROM action.intent_digest)) THEN
        RAISE EXCEPTION 'GL_NATIVE_COMPLETE: exact audit and outbox required' USING ERRCODE='23514';
    END IF;
    IF action.kind='ABSENT_CLOSED' THEN
        IF EXISTS(SELECT 1 FROM public.gl_native_posting WHERE action_id=action.action_id) THEN
            RAISE EXCEPTION 'GL_NATIVE_COMPLETE: closed action cannot post' USING ERRCODE='23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO posting FROM public.gl_native_posting WHERE action_id=action.action_id;
    IF NOT FOUND OR posting.id IS DISTINCT FROM action.posting_id
        OR posting.kind<>action.kind THEN
        RAISE EXCEPTION 'GL_NATIVE_COMPLETE: exact posting receipt required' USING ERRCODE='23514';
    END IF;
    SELECT * INTO book FROM public.gl_native_book WHERE id=posting.book_id AND tenant_id=posting.tenant_id FOR NO KEY UPDATE;
    SELECT * INTO period FROM public.gl_native_period WHERE id=posting.period_id AND tenant_id=posting.tenant_id AND book_id=posting.book_id FOR NO KEY UPDATE;
    IF book.lifecycle IS DISTINCT FROM 'ACTIVE' OR period.lifecycle IS DISTINCT FROM 'OPEN'
        OR book.control_version<>posting.expected_book_version OR period.control_version<>posting.expected_period_version
        OR posting.business_date<period.from_date OR posting.business_date>=period.to_date THEN
        RAISE EXCEPTION 'GL_NATIVE_COMPLETE: active book and open exact period required' USING ERRCODE='23514';
    END IF;
    FOR gate IN SELECT DISTINCT account_id FROM public.gl_native_line WHERE action_id=action.action_id ORDER BY account_id LOOP
        SELECT * INTO account FROM public.gl_native_account WHERE account_id=gate FOR NO KEY UPDATE;
        IF NOT FOUND OR account.kind<>'POSTING' OR account.lifecycle<>'ACTIVE'
            OR EXISTS(SELECT 1 FROM public.gl_native_line l WHERE l.action_id=action.action_id AND l.account_id=gate
                AND l.expected_account_version<>account.control_version)
            OR account.tenant_id<>posting.tenant_id OR account.book_id<>posting.book_id THEN
            RAISE EXCEPTION 'GL_NATIVE_COMPLETE: active exact posting account required' USING ERRCODE='23514';
        END IF;
    END LOOP;
    FOR gate IN SELECT DISTINCT currency_admission_id FROM public.gl_native_line WHERE action_id=action.action_id ORDER BY currency_admission_id LOOP
        SELECT * INTO currency FROM public.gl_native_currency_admission WHERE id=gate FOR NO KEY UPDATE;
        IF NOT FOUND OR currency.lifecycle<>'ACTIVE'
            OR EXISTS(SELECT 1 FROM public.gl_native_line l WHERE l.action_id=action.action_id AND l.currency_admission_id=gate
                AND l.expected_currency_version<>currency.control_version) THEN
            RAISE EXCEPTION 'GL_NATIVE_COMPLETE: active retained currency admission required' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF action.authority_until_epoch_millis<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN
        RAISE EXCEPTION 'GL_NATIVE_COMPLETE: authority expired while waiting for admission gates' USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO entry_count FROM public.gl_native_entry WHERE action_id=action.action_id;
    SELECT count(*) INTO line_count FROM public.gl_native_line WHERE action_id=action.action_id;
    IF entry_count NOT BETWEEN 1 AND 50 OR line_count NOT BETWEEN 2 AND 500
        OR action.account_ids IS DISTINCT FROM (SELECT array_agg(DISTINCT l.account_id ORDER BY l.account_id) FROM public.gl_native_line l WHERE l.action_id=action.action_id)
        OR (SELECT count(DISTINCT currency_admission_id) FROM public.gl_native_line WHERE action_id=action.action_id)>32
        OR EXISTS(SELECT 1 FROM public.gl_native_entry e LEFT JOIN public.gl_native_line l
            ON l.posting_id=e.posting_id AND l.entry_key=e.entry_key WHERE e.action_id=action.action_id
            GROUP BY e.entry_key HAVING count(l.line_key)<2)
        OR EXISTS(SELECT 1 FROM public.gl_native_line WHERE action_id=action.action_id
            GROUP BY entry_key,currency_admission_id
            HAVING sum(CASE side WHEN 'DR' THEN amount_value ELSE -amount_value END)<>0)
        OR EXISTS(SELECT 1 FROM public.gl_native_line WHERE action_id=action.action_id
            GROUP BY currency_admission_id,side HAVING sum(amount_value)>99999999999999999999999999999999999999) THEN
        RAISE EXCEPTION 'GL_NATIVE_COMPLETE: complete bounded per-entry currency balance required' USING ERRCODE='23514';
    END IF;
    SELECT last_sequence INTO sequence FROM public.gl_native_book_sequence WHERE book_id=posting.book_id;
    IF sequence IS NULL OR sequence<>(SELECT max(journal_sequence) FROM public.gl_native_posting WHERE book_id=posting.book_id) THEN
        RAISE EXCEPTION 'GL_NATIVE_SEQUENCE: atomic checked sequence required' USING ERRCODE='23514';
    END IF;
    IF posting.kind='REVERSE' THEN
        SELECT * INTO original FROM public.gl_native_posting WHERE id=posting.original_posting_id;
        IF NOT FOUND OR original.kind<>'POST' OR original.tenant_id<>posting.tenant_id OR original.book_id<>posting.book_id
            OR EXISTS((SELECT entry_key,line_key,account_id,currency_admission_id,amount_minor,
                CASE side WHEN 'DR' THEN 'CR' ELSE 'DR' END FROM public.gl_native_line WHERE posting_id=original.id)
                EXCEPT (SELECT entry_key,line_key,account_id,currency_admission_id,amount_minor,side FROM public.gl_native_line WHERE posting_id=posting.id))
            OR EXISTS((SELECT entry_key,line_key,account_id,currency_admission_id,amount_minor,side FROM public.gl_native_line WHERE posting_id=posting.id)
                EXCEPT (SELECT entry_key,line_key,account_id,currency_admission_id,amount_minor,
                CASE side WHEN 'DR' THEN 'CR' ELSE 'DR' END FROM public.gl_native_line WHERE posting_id=original.id)) THEN
            RAISE EXCEPTION 'GL_NATIVE_REVERSAL: one exact retained opposite journal required' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER gl_action_complete AFTER INSERT ON public.gl_native_journal_action DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_complete();
CREATE CONSTRAINT TRIGGER gl_posting_complete AFTER INSERT ON public.gl_native_posting DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_complete();
CREATE CONSTRAINT TRIGGER gl_entry_complete AFTER INSERT ON public.gl_native_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_complete();
CREATE CONSTRAINT TRIGGER gl_line_complete AFTER INSERT ON public.gl_native_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_complete();
CREATE CONSTRAINT TRIGGER gl_event_complete AFTER INSERT ON public.gl_native_journal_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.gl_native_journal_complete();

GRANT CREATE ON SCHEMA public TO gl_native_guard;
ALTER FUNCTION public.gl_native_sequence_complete() OWNER TO gl_native_guard;
ALTER FUNCTION public.gl_native_journal_complete() OWNER TO gl_native_guard;
REVOKE CREATE ON SCHEMA public FROM gl_native_guard;
REVOKE ALL ON FUNCTION public.gl_native_sequence_complete() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_journal_complete() FROM PUBLIC;
