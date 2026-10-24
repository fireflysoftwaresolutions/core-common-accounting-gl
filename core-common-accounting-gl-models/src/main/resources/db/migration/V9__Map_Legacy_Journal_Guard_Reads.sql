-- Invoker views preserve the legacy guards' database-side joins and base-table privileges.
CREATE VIEW public.gl_legacy_journal_batch_state WITH (security_invoker = true) AS
SELECT b.id, b.status::text AS status,
       EXISTS (SELECT 1 FROM public.journal_entry e WHERE e.batch_id = b.id AND e.posted) AS posted
FROM public.journal_batch b;

CREATE VIEW public.gl_legacy_journal_entry_state WITH (security_invoker = true) AS
SELECT e.id, e.batch_id, b.status::text AS status, e.posted
FROM public.journal_entry e
JOIN public.journal_batch b ON b.id = e.batch_id;

CREATE VIEW public.gl_legacy_account_usage WITH (security_invoker = true) AS
SELECT a.id,
       EXISTS (
           SELECT 1 FROM public.journal_line l
           JOIN public.journal_entry e ON e.id = l.entry_id
           JOIN public.journal_batch b ON b.id = e.batch_id
           WHERE l.account_id = a.id AND (e.posted OR b.status <> 'OPEN')
       ) AS historical
FROM public.gl_account a;
