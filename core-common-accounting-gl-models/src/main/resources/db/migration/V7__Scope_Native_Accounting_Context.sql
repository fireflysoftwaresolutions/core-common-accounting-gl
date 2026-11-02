-- Administrative INSERT/UPDATE remains closed until complete administrative
-- action receipts, audit and outbox are implemented. These policies admit only
-- current authorized financial work and retained observation.
CREATE FUNCTION public.gl_native_context_matches(tenant UUID,book UUID,operations TEXT[]) RETURNS boolean
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE expiry TEXT:=current_setting('firefly.gl.authority_until',true);
BEGIN
    IF expiry IS NULL OR expiry !~ '^[0-9]{1,15}$' THEN RETURN false; END IF;
    RETURN tenant::text=current_setting('firefly.gl.tenant',true)
        AND book::text=current_setting('firefly.gl.book',true)
        AND current_setting('firefly.gl.operation',true)=ANY(operations)
        AND current_setting('firefly.gl.xid',true)=pg_current_xact_id_if_assigned()::text
        AND expiry::bigint>floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;

CREATE FUNCTION public.gl_native_account_allowed(account UUID) RETURNS boolean
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE accounts JSONB;
BEGIN
    accounts:=current_setting('firefly.gl.accounts',true)::jsonb;
    RETURN jsonb_typeof(accounts)='array' AND jsonb_array_length(accounts) BETWEEN 1 AND 500
        AND accounts ? account::text;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;

CREATE FUNCTION public.gl_native_action_visible(tenant UUID,book UUID,action UUID) RETURNS boolean
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
    SELECT public.gl_native_context_matches(tenant,book,ARRAY['post','reverse','reconcile','read','history'])
        AND EXISTS(SELECT 1 FROM public.gl_native_journal_action a
            WHERE a.tenant_id=tenant AND a.book_id=book AND a.action_id=action
            AND NOT EXISTS(SELECT 1 FROM unnest(a.account_ids) account
                WHERE NOT public.gl_native_account_allowed(account)))
$$;

CREATE FUNCTION public.gl_native_discover_posting_accounts(tenant UUID,book UUID,posting UUID)
RETURNS TABLE(account_id UUID)
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
    SELECT account FROM public.gl_native_posting p
        JOIN public.gl_native_journal_action a ON a.tenant_id=p.tenant_id AND a.book_id=p.book_id AND a.action_id=p.action_id
        CROSS JOIN LATERAL unnest(a.account_ids) account
    WHERE public.gl_native_context_matches(tenant,book,ARRAY['read.resources'])
        AND posting::text=current_setting('firefly.gl.posting',true)
        AND p.tenant_id=tenant AND p.book_id=book AND p.id=posting
    ORDER BY account
$$;

GRANT CREATE ON SCHEMA public TO gl_native_guard;
ALTER FUNCTION public.gl_native_action_visible(UUID,UUID,UUID) OWNER TO gl_native_guard;
ALTER FUNCTION public.gl_native_discover_posting_accounts(UUID,UUID,UUID) OWNER TO gl_native_guard;
REVOKE CREATE ON SCHEMA public FROM gl_native_guard;
REVOKE ALL ON FUNCTION public.gl_native_context_matches(UUID,UUID,TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_account_allowed(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_action_visible(UUID,UUID,UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.gl_native_discover_posting_accounts(UUID,UUID,UUID) FROM PUBLIC;
-- Predicate helpers return booleans only. The one discovery function returns
-- bounded UUIDs only after the exact explicit resource-discovery admission.
GRANT EXECUTE ON FUNCTION public.gl_native_context_matches(UUID,UUID,TEXT[]),
    public.gl_native_account_allowed(UUID),public.gl_native_action_visible(UUID,UUID,UUID),
    public.gl_native_discover_posting_accounts(UUID,UUID,UUID) TO PUBLIC;

CREATE POLICY gl_native_book_read ON public.gl_native_book FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,id,ARRAY['post','reverse','reconcile','read','history','balances',
        'book.enroll','book.control','currency.install','currency.control','account.enroll','account.control','period.define','period.control','mapping.publish']));
CREATE POLICY gl_native_currency_read ON public.gl_native_currency_admission FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile','read','history','balances',
        'book.enroll','currency.install','currency.control','account.enroll','account.control']));
CREATE POLICY gl_native_account_read ON public.gl_native_account FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile','read','history','balances','account.enroll','account.control'])
    AND public.gl_native_account_allowed(account_id));
CREATE POLICY gl_native_period_read ON public.gl_native_period FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile','read','history','period.define','period.control']));
CREATE POLICY gl_native_mapping_read ON public.gl_native_mapping FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile','read','history','mapping.publish']));

CREATE POLICY gl_native_sequence_read ON public.gl_native_book_sequence FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse']));
CREATE POLICY gl_native_sequence_insert ON public.gl_native_book_sequence FOR INSERT TO PUBLIC WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse']));
CREATE POLICY gl_native_sequence_update ON public.gl_native_book_sequence FOR UPDATE TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse'])) WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse']));

CREATE POLICY gl_native_action_read ON public.gl_native_journal_action FOR SELECT TO PUBLIC USING (
    public.gl_native_action_visible(tenant_id,book_id,action_id));
CREATE POLICY gl_native_action_insert ON public.gl_native_journal_action FOR INSERT TO PUBLIC WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile'])
    AND action_id::text=current_setting('firefly.gl.action',true)
    AND intent_digest=current_setting('firefly.gl.intent',true)
    AND actor_issuer=current_setting('firefly.gl.actor_issuer',true)
    AND actor_subject=current_setting('firefly.gl.actor_subject',true)
    AND to_jsonb(account_ids)=current_setting('firefly.gl.accounts',true)::jsonb
    AND authority_until_epoch_millis<=current_setting('firefly.gl.authority_until',true)::bigint
    AND kind=CASE current_setting('firefly.gl.operation',true) WHEN 'post' THEN 'POST' WHEN 'reverse' THEN 'REVERSE' WHEN 'reconcile' THEN 'ABSENT_CLOSED' END);
CREATE POLICY gl_native_posting_read ON public.gl_native_posting FOR SELECT TO PUBLIC USING (
    public.gl_native_action_visible(tenant_id,book_id,action_id));
CREATE POLICY gl_native_posting_insert ON public.gl_native_posting FOR INSERT TO PUBLIC WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse'])
    AND action_id::text=current_setting('firefly.gl.action',true)
    AND kind=CASE current_setting('firefly.gl.operation',true) WHEN 'post' THEN 'POST' WHEN 'reverse' THEN 'REVERSE' END);
CREATE POLICY gl_native_entry_read ON public.gl_native_entry FOR SELECT TO PUBLIC USING (
    public.gl_native_action_visible(tenant_id,book_id,action_id));
CREATE POLICY gl_native_entry_insert ON public.gl_native_entry FOR INSERT TO PUBLIC WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse'])
    AND action_id::text=current_setting('firefly.gl.action',true));
CREATE POLICY gl_native_line_read ON public.gl_native_line FOR SELECT TO PUBLIC USING (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile','read','history','balances'])
    AND public.gl_native_account_allowed(account_id)
    AND (current_setting('firefly.gl.operation',true)='balances'
        OR public.gl_native_action_visible(tenant_id,book_id,action_id)));
CREATE POLICY gl_native_line_insert ON public.gl_native_line FOR INSERT TO PUBLIC WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse'])
    AND action_id::text=current_setting('firefly.gl.action',true)
    AND public.gl_native_account_allowed(account_id));
CREATE POLICY gl_native_event_read ON public.gl_native_journal_event FOR SELECT TO PUBLIC USING (
    public.gl_native_action_visible(tenant_id,book_id,action_id));
CREATE POLICY gl_native_event_insert ON public.gl_native_journal_event FOR INSERT TO PUBLIC WITH CHECK (
    public.gl_native_context_matches(tenant_id,book_id,ARRAY['post','reverse','reconcile'])
    AND action_id::text=current_setting('firefly.gl.action',true));
