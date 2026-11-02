-- Legacy journals remain editable OPEN drafts. Financial posting belongs to the atomic owner.
-- Existing non-OPEN/posted history is retained verbatim and cannot be rewritten or deleted.
ALTER TABLE public.gl_account ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE public.journal_batch ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE public.journal_entry ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE public.journal_line ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE FUNCTION public.gl_legacy_require_draft_batch(batch_uuid uuid) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.journal_batch b
                   WHERE b.id=batch_uuid AND b.status='OPEN')
       OR EXISTS (SELECT 1 FROM public.journal_entry e WHERE e.batch_id=batch_uuid AND e.posted) THEN
        RAISE EXCEPTION 'GL_LEGACY_DRAFT_REQUIRED' USING ERRCODE='23514';
    END IF;
END;
$$;

CREATE FUNCTION public.gl_legacy_require_draft_entry(entry_uuid uuid) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE parent_uuid uuid; is_posted boolean;
BEGIN
    SELECT e.batch_id,e.posted INTO parent_uuid,is_posted FROM public.journal_entry e WHERE e.id=entry_uuid;
    IF parent_uuid IS NULL OR is_posted THEN
        RAISE EXCEPTION 'GL_LEGACY_DRAFT_REQUIRED' USING ERRCODE='23514';
    END IF;
    PERFORM public.gl_legacy_require_draft_batch(parent_uuid);
END;
$$;

CREATE FUNCTION public.gl_legacy_batch_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF OLD.status <> 'OPEN'
           OR EXISTS (SELECT 1 FROM public.journal_entry e WHERE e.batch_id=OLD.id AND e.posted) THEN
            RAISE EXCEPTION 'GL_LEGACY_HISTORY_IMMUTABLE' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.status <> 'OPEN' OR (TG_OP='UPDATE' AND NEW.id IS DISTINCT FROM OLD.id) THEN
        RAISE EXCEPTION 'GL_LEGACY_ATOMIC_POSTING_REQUIRED' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.gl_legacy_entry_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF OLD.posted THEN
            RAISE EXCEPTION 'GL_LEGACY_HISTORY_IMMUTABLE' USING ERRCODE='23514';
        END IF;
        PERFORM public.gl_legacy_require_draft_batch(OLD.batch_id);
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.posted OR (TG_OP='UPDATE' AND
            (NEW.id IS DISTINCT FROM OLD.id OR NEW.batch_id IS DISTINCT FROM OLD.batch_id)) THEN
        RAISE EXCEPTION 'GL_LEGACY_ATOMIC_POSTING_REQUIRED' USING ERRCODE='23514';
    END IF;
    PERFORM public.gl_legacy_require_draft_batch(NEW.batch_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.gl_legacy_line_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM public.gl_legacy_require_draft_entry(OLD.entry_id);
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF TG_OP='UPDATE' AND (NEW.id IS DISTINCT FROM OLD.id OR NEW.entry_id IS DISTINCT FROM OLD.entry_id) THEN
        RAISE EXCEPTION 'GL_LEGACY_PARENT_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    PERFORM public.gl_legacy_require_draft_entry(NEW.entry_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER gl_legacy_batch_guard BEFORE INSERT OR UPDATE OR DELETE ON public.journal_batch
FOR EACH ROW EXECUTE FUNCTION public.gl_legacy_batch_guard();
CREATE TRIGGER gl_legacy_entry_guard BEFORE INSERT OR UPDATE OR DELETE ON public.journal_entry
FOR EACH ROW EXECUTE FUNCTION public.gl_legacy_entry_guard();
CREATE TRIGGER gl_legacy_line_guard BEFORE INSERT OR UPDATE OR DELETE ON public.journal_line
FOR EACH ROW EXECUTE FUNCTION public.gl_legacy_line_guard();


CREATE FUNCTION public.gl_legacy_account_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.journal_line l
               JOIN public.journal_entry e ON e.id=l.entry_id
               JOIN public.journal_batch b ON b.id=e.batch_id
               WHERE l.account_id=OLD.id AND (e.posted OR b.status<>'OPEN')) THEN
        RAISE EXCEPTION 'GL_LEGACY_HISTORY_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'GL_LEGACY_PARENT_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gl_legacy_account_guard BEFORE UPDATE OR DELETE ON public.gl_account
FOR EACH ROW EXECUTE FUNCTION public.gl_legacy_account_guard();
