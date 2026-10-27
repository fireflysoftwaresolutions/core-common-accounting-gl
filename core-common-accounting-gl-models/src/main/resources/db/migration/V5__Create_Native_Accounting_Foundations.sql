-- Native definitions remain inaccessible to application roles until the owner
-- transaction/context and complete action-receipt policies are installed.
CREATE TABLE public.gl_native_book (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    legal_entity_reference VARCHAR(255) NOT NULL CHECK (btrim(legal_entity_reference) <> ''),
    book_code VARCHAR(100) NOT NULL CHECK (btrim(book_code) <> ''),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    canonical_definition TEXT NOT NULL,
    tenant_observation_digest TEXT NOT NULL CHECK (tenant_observation_digest ~ '^[0-9a-f]{64}$'),
    timezone VARCHAR(100) NOT NULL,
    calendar_reference VARCHAR(255) NOT NULL,
    calendar_version BIGINT NOT NULL CHECK (calendar_version > 0),
    calendar_digest TEXT NOT NULL CHECK (calendar_digest ~ '^[0-9a-f]{64}$'),
    functional_currency_admission_id UUID NOT NULL,
    enrollment_action_id UUID NOT NULL UNIQUE,
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE','SUSPENDED')),
    control_version BIGINT NOT NULL DEFAULT 1 CHECK (control_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,legal_entity_reference,book_code),
    CHECK (octet_length(canonical_definition) BETWEEN 1 AND 1048576),
    CHECK (definition_digest=encode(sha256(convert_to(canonical_definition,'UTF8')),'hex'))
);

CREATE TABLE public.gl_native_currency_admission (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    currency_id UUID NOT NULL,
    iso_code TEXT NOT NULL CHECK (iso_code ~ '^[A-Z]{3}$'),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    policy_digest TEXT NOT NULL CHECK (policy_digest ~ '^[0-9a-f]{64}$'),
    policy_encoding TEXT NOT NULL CHECK (policy_encoding='MASTERS_CURRENCY_POLICY_V1'),
    canonical_policy TEXT NOT NULL,
    posted_scale INTEGER NOT NULL CHECK (posted_scale BETWEEN 0 AND 10),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    canonical_definition TEXT NOT NULL,
    enrollment_action_id UUID NOT NULL UNIQUE,
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE','SUSPENDED')),
    control_version BIGINT NOT NULL DEFAULT 1 CHECK (control_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,book_id,id),
    UNIQUE (tenant_id,book_id,policy_id,policy_version,policy_digest),
    CHECK (octet_length(canonical_definition) BETWEEN 1 AND 1048576),
    CHECK (octet_length(canonical_policy) BETWEEN 1 AND 1048576),
    CHECK (definition_digest=encode(sha256(convert_to(canonical_definition,'UTF8')),'hex')),
    CHECK (policy_digest=encode(sha256(convert_to(canonical_policy,'UTF8')),'hex')),
    FOREIGN KEY (tenant_id,book_id) REFERENCES public.gl_native_book(tenant_id,id)
        DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE public.gl_native_book ADD CONSTRAINT gl_book_functional_currency_scope
    FOREIGN KEY (tenant_id,id,functional_currency_admission_id)
    REFERENCES public.gl_native_currency_admission(tenant_id,book_id,id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.gl_native_account (
    account_id UUID PRIMARY KEY REFERENCES public.gl_account(id),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('CONTROL','POSTING')),
    parent_account_id UUID,
    currency_admission_id UUID,
    source_code VARCHAR(50) NOT NULL,
    source_type TEXT NOT NULL CHECK (source_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    source_currency TEXT NOT NULL CHECK (source_currency ~ '^[A-Z]{3}$'),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    canonical_definition TEXT NOT NULL,
    enrollment_action_id UUID NOT NULL UNIQUE,
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE','SUSPENDED')),
    control_version BIGINT NOT NULL DEFAULT 1 CHECK (control_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,book_id,account_id),
    CHECK (account_id IS DISTINCT FROM parent_account_id),
    CHECK (octet_length(canonical_definition) BETWEEN 1 AND 1048576),
    CHECK (definition_digest=encode(sha256(convert_to(canonical_definition,'UTF8')),'hex')),
    CHECK ((kind='POSTING' AND currency_admission_id IS NOT NULL)
        OR (kind='CONTROL' AND currency_admission_id IS NULL)),
    FOREIGN KEY (tenant_id,book_id) REFERENCES public.gl_native_book(tenant_id,id),
    FOREIGN KEY (tenant_id,book_id,parent_account_id)
        REFERENCES public.gl_native_account(tenant_id,book_id,account_id),
    FOREIGN KEY (tenant_id,book_id,currency_admission_id)
        REFERENCES public.gl_native_currency_admission(tenant_id,book_id,id)
);

CREATE TABLE public.gl_native_period (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    from_date DATE NOT NULL CHECK (from_date >= DATE '1900-01-01'),
    to_date DATE NOT NULL CHECK (to_date <= DATE '9999-12-31'),
    timezone VARCHAR(100) NOT NULL,
    calendar_reference VARCHAR(255) NOT NULL,
    calendar_version BIGINT NOT NULL CHECK (calendar_version > 0),
    calendar_digest TEXT NOT NULL CHECK (calendar_digest ~ '^[0-9a-f]{64}$'),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    canonical_definition TEXT NOT NULL,
    enrollment_action_id UUID NOT NULL UNIQUE,
    lifecycle TEXT NOT NULL DEFAULT 'OPEN' CHECK (lifecycle IN ('OPEN','FROZEN')),
    control_version BIGINT NOT NULL DEFAULT 1 CHECK (control_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,book_id,id),
    CHECK (from_date < to_date),
    CHECK (octet_length(canonical_definition) BETWEEN 1 AND 1048576),
    CHECK (definition_digest=encode(sha256(convert_to(canonical_definition,'UTF8')),'hex')),
    FOREIGN KEY (tenant_id,book_id) REFERENCES public.gl_native_book(tenant_id,id)
);

CREATE TABLE public.gl_native_mapping (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    mapping_reference VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    canonical_definition TEXT NOT NULL,
    enrollment_action_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id,book_id,id),
    UNIQUE (tenant_id,book_id,mapping_reference,revision),
    CHECK (octet_length(canonical_definition) BETWEEN 1 AND 1048576),
    CHECK (definition_digest=encode(sha256(convert_to(canonical_definition,'UTF8')),'hex')),
    FOREIGN KEY (tenant_id,book_id) REFERENCES public.gl_native_book(tenant_id,id)
);

ALTER TABLE public.gl_native_book ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_book FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_currency_admission ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_currency_admission FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_account FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_period FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_native_mapping FORCE ROW LEVEL SECURITY;

-- The trigger helper can inspect hidden enrollment metadata, but cannot log in,
-- own protected relations, write native definitions, or expose a query API.
CREATE ROLE gl_native_guard NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO gl_native_guard;
GRANT SELECT ON public.gl_native_book,public.gl_native_currency_admission,
    public.gl_native_account,public.gl_native_period,public.gl_native_mapping,
    public.gl_account,public.journal_line TO gl_native_guard;
GRANT UPDATE ON public.gl_account,public.gl_native_book TO gl_native_guard;
CREATE POLICY gl_guard_book ON public.gl_native_book FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_currency ON public.gl_native_currency_admission FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_account ON public.gl_native_account FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_period ON public.gl_native_period FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_mapping ON public.gl_native_mapping FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_book_lock ON public.gl_native_book FOR UPDATE TO gl_native_guard USING (true) WITH CHECK (false);

CREATE FUNCTION public.gl_native_definition_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'GL_NATIVE_IMMUTABLE: retained definition cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF to_jsonb(NEW)-ARRAY['lifecycle','control_version','updated_at']
        IS DISTINCT FROM to_jsonb(OLD)-ARRAY['lifecycle','control_version','updated_at']
        OR OLD.control_version=9223372036854775807
        OR NEW.control_version<>OLD.control_version+1
        OR NEW.lifecycle=OLD.lifecycle THEN
        RAISE EXCEPTION 'GL_NATIVE_IMMUTABLE: exact admission transition required' USING ERRCODE='23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER gl_book_definition_guard BEFORE UPDATE OR DELETE ON public.gl_native_book
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_definition_guard();
CREATE TRIGGER gl_currency_definition_guard BEFORE UPDATE OR DELETE ON public.gl_native_currency_admission
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_definition_guard();
CREATE TRIGGER gl_account_definition_guard BEFORE UPDATE OR DELETE ON public.gl_native_account
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_definition_guard();
CREATE TRIGGER gl_period_definition_guard BEFORE UPDATE OR DELETE ON public.gl_native_period
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_definition_guard();

CREATE FUNCTION public.gl_native_mapping_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'GL_NATIVE_IMMUTABLE: mapping revision is retained' USING ERRCODE='23514';
END $$;
CREATE TRIGGER gl_mapping_definition_guard BEFORE UPDATE OR DELETE ON public.gl_native_mapping
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_mapping_guard();

CREATE FUNCTION public.gl_native_account_enrollment_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE source public.gl_account%ROWTYPE; parent public.gl_native_account%ROWTYPE; gate UUID;
BEGIN
    -- Row locks precede advisory gates to match legacy UPDATE. NO KEY UPDATE
    -- remains compatible with a child's FK KEY SHARE while it owns a gate.
    FOR gate IN SELECT DISTINCT v FROM unnest(ARRAY[NEW.account_id,NEW.parent_account_id]) v
        WHERE v IS NOT NULL ORDER BY v LOOP
        PERFORM 1 FROM public.gl_account WHERE id=gate FOR NO KEY UPDATE;
    END LOOP;
    FOR gate IN SELECT DISTINCT v FROM unnest(ARRAY[NEW.account_id,NEW.parent_account_id]) v
        WHERE v IS NOT NULL ORDER BY v LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended('GL_CHART_V1:'||gate::text,0));
    END LOOP;
    SELECT * INTO source FROM public.gl_account WHERE id=NEW.account_id;
    IF NOT FOUND OR source.active IS DISTINCT FROM true
        OR source.code<>NEW.source_code OR source.type::text<>NEW.source_type
        OR source.currency<>NEW.source_currency
        OR source.parent_id IS DISTINCT FROM NEW.parent_account_id
        OR source.is_leaf IS DISTINCT FROM (NEW.kind='POSTING')
        OR EXISTS(SELECT 1 FROM public.journal_line WHERE account_id=NEW.account_id) THEN
        RAISE EXCEPTION 'GL_NATIVE_ACCOUNT: canonical unused source does not match' USING ERRCODE='23514';
    END IF;
    IF NEW.parent_account_id IS NOT NULL THEN
        SELECT * INTO parent FROM public.gl_native_account WHERE account_id=NEW.parent_account_id;
        IF NOT FOUND OR parent.tenant_id<>NEW.tenant_id OR parent.book_id<>NEW.book_id
            OR parent.kind<>'CONTROL' THEN
            RAISE EXCEPTION 'GL_NATIVE_ACCOUNT: enrolled same-book control parent required' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.kind='POSTING' AND NOT EXISTS (
        SELECT 1 FROM public.gl_native_currency_admission c WHERE c.id=NEW.currency_admission_id
        AND c.tenant_id=NEW.tenant_id AND c.book_id=NEW.book_id AND c.iso_code=source.currency) THEN
        RAISE EXCEPTION 'GL_NATIVE_ACCOUNT: exact same-book currency required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_native_account_enrollment_guard BEFORE INSERT ON public.gl_native_account
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_account_enrollment_guard();

CREATE FUNCTION public.gl_native_legacy_account_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE ids UUID[]; gate UUID;
BEGIN
    IF TG_OP='INSERT' THEN ids:=ARRAY[NEW.id,NEW.parent_id];
    ELSIF TG_OP='DELETE' THEN ids:=ARRAY[OLD.id,OLD.parent_id];
    ELSE ids:=ARRAY[OLD.id,NEW.id,OLD.parent_id,NEW.parent_id]; END IF;
    FOR gate IN SELECT DISTINCT v FROM unnest(ids) v WHERE v IS NOT NULL ORDER BY v LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended('GL_CHART_V1:'||gate::text,0));
    END LOOP;
    IF TG_OP<>'INSERT' AND EXISTS(SELECT 1 FROM public.gl_native_account WHERE account_id=OLD.id) THEN
        RAISE EXCEPTION 'GL_NATIVE_ACCOUNT: managed chart uses native controls' USING ERRCODE='23514';
    END IF;
    IF TG_OP<>'DELETE' AND (TG_OP='INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id)
        AND EXISTS(SELECT 1 FROM public.gl_native_account WHERE account_id=NEW.parent_id) THEN
        RAISE EXCEPTION 'GL_NATIVE_ACCOUNT: generic child injection denied' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_native_legacy_account_guard BEFORE INSERT OR UPDATE OR DELETE ON public.gl_account
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_legacy_account_guard();

CREATE FUNCTION public.gl_native_legacy_line_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE ids UUID[]; gate UUID;
BEGIN
    IF TG_OP='INSERT' THEN ids:=ARRAY[NEW.account_id];
    ELSIF TG_OP='DELETE' THEN ids:=ARRAY[OLD.account_id];
    ELSE ids:=ARRAY[OLD.account_id,NEW.account_id]; END IF;
    FOR gate IN SELECT DISTINCT v FROM unnest(ids) v WHERE v IS NOT NULL ORDER BY v LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended('GL_CHART_V1:'||gate::text,0));
    END LOOP;
    IF EXISTS(SELECT 1 FROM public.gl_native_account WHERE account_id=ANY(ids)) THEN
        RAISE EXCEPTION 'GL_NATIVE_ACCOUNT: generic money writes denied' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_native_legacy_line_guard BEFORE INSERT OR UPDATE OR DELETE ON public.journal_line
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_legacy_line_guard();

CREATE FUNCTION public.gl_native_period_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE book public.gl_native_book%ROWTYPE;
BEGIN
    SELECT * INTO book FROM public.gl_native_book WHERE tenant_id=NEW.tenant_id AND id=NEW.book_id FOR NO KEY UPDATE;
    IF NOT FOUND OR book.timezone<>NEW.timezone OR book.calendar_reference<>NEW.calendar_reference
        OR book.calendar_version<>NEW.calendar_version OR book.calendar_digest<>NEW.calendar_digest
        OR EXISTS(SELECT 1 FROM public.gl_native_period p WHERE p.tenant_id=NEW.tenant_id AND p.book_id=NEW.book_id
            AND p.from_date<NEW.to_date AND NEW.from_date<p.to_date) THEN
        RAISE EXCEPTION 'GL_NATIVE_PERIOD: coherent nonoverlapping book period required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER gl_native_period_guard BEFORE INSERT ON public.gl_native_period
    FOR EACH ROW EXECUTE FUNCTION public.gl_native_period_guard();

GRANT CREATE ON SCHEMA public TO gl_native_guard;
ALTER FUNCTION public.gl_native_account_enrollment_guard() OWNER TO gl_native_guard;
ALTER FUNCTION public.gl_native_legacy_account_guard() OWNER TO gl_native_guard;
ALTER FUNCTION public.gl_native_legacy_line_guard() OWNER TO gl_native_guard;
ALTER FUNCTION public.gl_native_period_guard() OWNER TO gl_native_guard;
REVOKE CREATE ON SCHEMA public FROM gl_native_guard;
REVOKE ALL ON FUNCTION public.gl_native_account_enrollment_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_legacy_account_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_legacy_line_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_period_guard() FROM PUBLIC;

CREATE FUNCTION public.gl_native_legacy_chart_visible(account UUID) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
    SELECT NOT EXISTS(SELECT 1 FROM public.gl_native_account WHERE account_id=account)
$$;
GRANT CREATE ON SCHEMA public TO gl_native_guard;
ALTER FUNCTION public.gl_native_legacy_chart_visible(UUID) OWNER TO gl_native_guard;
REVOKE CREATE ON SCHEMA public FROM gl_native_guard;
REVOKE ALL ON FUNCTION public.gl_native_legacy_chart_visible(UUID) FROM PUBLIC;
-- This policy predicate returns visibility only; it never returns owner or
-- financial metadata and does not admit a native service context.
GRANT EXECUTE ON FUNCTION public.gl_native_legacy_chart_visible(UUID) TO PUBLIC;
ALTER TABLE public.gl_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gl_account FORCE ROW LEVEL SECURITY;
CREATE POLICY gl_guard_source_chart ON public.gl_account FOR SELECT TO gl_native_guard USING (true);
CREATE POLICY gl_guard_source_chart_lock ON public.gl_account FOR UPDATE TO gl_native_guard USING (true) WITH CHECK (false);
CREATE POLICY gl_legacy_chart_read ON public.gl_account FOR SELECT TO PUBLIC
    USING (public.gl_native_legacy_chart_visible(id));
CREATE POLICY gl_legacy_chart_insert ON public.gl_account FOR INSERT TO PUBLIC
    WITH CHECK (public.gl_native_legacy_chart_visible(id));
CREATE POLICY gl_legacy_chart_update ON public.gl_account FOR UPDATE TO PUBLIC
    USING (public.gl_native_legacy_chart_visible(id)) WITH CHECK (public.gl_native_legacy_chart_visible(id));
CREATE POLICY gl_legacy_chart_delete ON public.gl_account FOR DELETE TO PUBLIC
    USING (public.gl_native_legacy_chart_visible(id));
