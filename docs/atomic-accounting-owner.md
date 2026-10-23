# Atomic accounting owner foundations

This source checkpoint establishes the native GL data model, exact journal invariants, current authority boundary, and scoped database context. Native enrollment/control receipts, CQRS financial handlers, protected owner APIs, a newly generated owner client, and source-to-GL delivery are still being implemented. Existing accounting and loan consumers must migrate before production rollout because legacy journal routes now support drafting and refuse new financial finalization.

## Financial ownership and representations

The GL owns retained book definitions, currency admissions, chart-account enrollment, accounting periods, mapping revisions, immutable journals, and journal sequencing. Currency representation metadata comes from the exact versioned reference published by the masters currency-policy owner. The GL never selects the latest policy implicitly and never treats mutable ISO metadata as approval to post.

Each currency reference includes its policy ID, currency ID, ISO code, version, SHA-256 digest, and encoding version. Its immutable canonical bytes and posted scale are retained in a book-local admission. Initial installation requires the exact policy to be PUBLISHED and currently effective. Once installed, explicit GL ACTIVE/SUSPENDED admission controls govern its use. Later masters retirement or effectivity end does not reprice or silently disable an already admitted representation.

Chart enrollment distinguishes CONTROL accounts from POSTING accounts. A CONTROL account must match an actual non-leaf source record and cannot appear in financial lines. A POSTING account must match an actual leaf and have its own explicit currency admission. A non-root account requires an already enrolled same-book CONTROL parent. Enrollment does not infer tenant ownership from a global source-account UUID or inherit financial authority from a parent.

The server derives the book UUID with the versioned GL_BOOK_ID_V1 namespace and the trusted tenant/enrollment action. This identifies a retained resource; it does not prove ownership or replace approval.

## Exact money and journal completeness

Amounts are positive canonical minor-unit strings. The side is explicitly DR or CR. Decimal fractions, exponents, signs, whitespace, zero, leading zeros, and amounts over38 digits are rejected. PostgreSQL stores the original canonical TEXT and a generated NUMERIC(38,0) arithmetic value so a raw fractional input cannot be rounded into an accepted integer before validation.

A journal is bounded to50 entries,500 lines, and32 currency representations. Every entry balances within each exact representation; currency identities and scales are not interchangeable. Per-entry and per-posting totals remain within38 digits. Cumulative account and trial-balance totals may legitimately be wider and will use exact signed-integer strings without a new cumulative admission ceiling.

The native schema requires one immutable action/intent receipt, its complete posting and lines, and both audit and outbox records in the same PostgreSQL transaction. Completion checks bind all rows to the action's transaction ID. A later transaction cannot append lines or events to complete or extend an already committed receipt. Book-local sequences are checked increments; missing outbox or incomplete balance rolls back the counter and the entire effect.

A reversal must be a new immutable exact opposite journal with a unique original-posting reference, current authority, active admissions, and an explicitly authorized OPEN adjustment period. The original journal stays retained. New REVERSE actions remain closed until their complete versioned original-reference/reason contract and vectors are implemented; the earlier SQL protocol is not a public reversal capability. No-effect closure has no posting and is admitted only at or after the ledger's authoritative deadline. Its stored request already binds the complete original posting intent; the public reconciliation command is still pending.

## Complete original request and economic rows

New POST and ABSENT_CLOSED actions require the closed GL_POSTING_REQUEST_V1 original request. The owner retains the exact first JSON text, the full GL_POSTING_INTENT_V1 canonical bytes, and the separate economic bytes and source identity. JSON object formatting and escapes may differ without changing semantic intent. Entry and line keys must already be strictly ordered; the owner never silently sorts an unauthorized body into an approved request.

The byte decoder rejects malformed UTF-8 before replacement decoding. Java and PostgreSQL agree on scalar Unicode, UTF-16 field limits, UTF-8 canonical lengths, exact integer tokens, UTC bounds, money strings, and every required field. The database validates raw unique JSON keys before using a generated JSONB lookup representation. Unknown, missing, null, escaped duplicate, fractional, exponential, overflowing, and noncanonical integer forms cannot disappear through normalization.

The insert guard binds the exact tenant/book/action, source key, deadline, account set, canonical bytes and digests. A new POST is logically admitted only when its action INSERT has acquired the actual book, period, distinct sorted account and distinct sorted currency row gates, validated their retained references and versions, and passed the final PostgreSQL-clock deadline and current-authority check. A gate wait that crosses the original cutoff cannot create a new posting. The nondeferred admission trigger holds those locks through transaction end; an action statement admitted before cutoff may finish its economic rows and commit afterward. Deferred completeness does not introduce a second request cutoff at commit. Exact native action authority is checked before helper-visible gate access and again after waits. ABSENT_CLOSED retains its dedicated postdeadline reconciliation authority without requiring active financial admissions. Deferred same-transaction checks bind every actual posting header, entry, line, expected version, currency-policy reference, amount, side, memo and row count to that original body. A balanced journal with the same totals but different row membership or account signs is refused. Physical SQL insertion order does not redefine the logical approved sequence. Audit/outbox failure or an outer transaction error rolls back all effects.

Actions created by the earlier foundation protocol remain explicitly GL_FOUNDATION_V1 with no invented original body. Migration preserves their bytes and immutable history. They are unverified foundation records, not complete approved financial proof, and new inserts cannot choose that shape. A future native service must refuse to treat those rows as an authoritative FOUND financial observation without an explicit reviewed migration.

## Current authority before transactions

Native operations require the existing SecurityContextPort and PolicyDecisionPort, a finite real principal, and explicit strict-policy opt-in:

```yaml
firefly:
  security:
    resource-server:
      strict-policy: true
  accounting:
    approval:
      trusted-issuers: ${ACCOUNTING_APPROVAL_TRUSTED_ISSUERS:}
```

Missing/false strict mode, missing policy/principal, empty decisions, malformed decisions, errors, timeouts, unsupported obligations, missing expiry, or expired authority fail closed. Book enrollment requires the project AccountingBookOwnerPort. Other native administrative and financial mutations require the project AccountingApprovalPort. Neither port has an implicit approving fallback. Approval must bind the complete immutable versioned canonical intent and exact trusted tenant/book/action/account/currency/period/mapping/source references; a digest alone is not semantic business approval.

Every distinct posting account needs its own current PDP decision in addition to the current book decision. Account checks are bounded, deduplicated by exact identity, and complete before database work. The admission retains only the accounts whose checks actually passed. A book grant alone never creates account grants.

All policy, approval, and external SDK observations run before the native SQL transaction. Ambient transactions are rejected before those callbacks. The native executor opens its own transaction, establishes its context on that same connection, rejects empty business results before commit, and rechecks finite authority before acknowledging a result. Cancellation and failures roll back.

## Database roles and scoped access

Migrations/bootstrap and the runtime application use separate roles. The runtime role must not be a superuser, BYPASSRLS role, owner/member of a protected-table owner, creator of public-schema objects, database/role creator, or member of gl_native_guard. All12 protected tables require enabled and forced RLS. The internal helper owner must be NOLOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEDB, NOCREATEROLE, and NOINHERIT, with a fixed search path and qualified table references. The runtime context checks these conditions before admission.

The service derives transaction-local tenant, book, operation, action, posting, intent digest, authorized accounts, principal issuer/subject, authority deadline, and transaction ID from its validated admission. Request headers and arbitrary caller UUID sets do not grant this context. SET LOCAL is scoped to the actual R2DBC transaction and must clear on commit, rollback, cancellation, and connection-pool reuse. These controls do not constrain a database administrator or make stolen SQL credentials safe.

Scoped policies distinguish the following access:

| Operation | Admitted access |
| --- | --- |
| POST | Exact current action, complete intent, principal, tenant/book and authorized accounts; immutable financial rows plus checked sequence and required events. New REVERSE actions remain closed pending their versioned complete contract. |
| RECONCILE | Retained authorized observation and an audited no-effect action; no journal-header/line/sequence writes and no administrative controls. |
| READ / HISTORY | Whole financial receipt/history requires current authority for every account in that receipt. |
| BALANCES | Exact authorized account lines for later bounded account projections; no inferred book-wide financial authority. |
| READ.RESOURCES | Only bounded account UUIDs for an exact tenant/book/posting under accounting.gl.read.resources; no full financial action, header, line, or event row. |
| Administrative mutations | Still closed at the database-policy stage until the shared administrative action/receipt/audit/outbox implementation is complete. |

Resource discovery closes its own transaction before subsequent per-account PDP checks. The final protected read establishes a new validated context. Missing/invisible discovery does not expose financial metadata. Retained authorized reads and outcome reconciliation remain available while a book/admission is suspended or a period is frozen; observing prior history must not require financial reactivation.

## Masters observation integration

SdkAccountingCurrencyObservationAdapter uses the actual generated CurrencyExecutionPoliciesApi client. A project must supply exactly one correctly configured authenticated client. The adapter checks the complete requested reference, canonical policy/source encodings and digests, required explicit-null effectivity fields, scale, publication identity, and initial admission status. It rejects ambient transactions and bounds external observation time. Errors are sanitized and never trigger a latest-policy fallback.

HTTPS is required by default. The explicit property firefly.accounting.currency-observation.allow-loopback-http=true permits only127.0.0.1 or ::1 for a private sandbox fixture. It does not authorize arbitrary HTTP destinations or create trusted production credentials.

## Legacy compatibility and migration

Existing OPEN batch/entry/line drafting remains available. New legacy POSTED/REVERSED batches, posted entries, and generic financial mutation/deletion are refused. Parent identities are checked across batch, entry, and line routes. Historical posted data and enrolled source-chart identities are fenced against mutable CRUD. Generic source-chart reads hide enrolled metadata while preserving unused legacy drafts.

The native financial API and migration adapters are not present in this checkpoint. A production cutover must first deliver protected native commands, the administrative receipt namespace, typed generated clients, exact source observations, retained read/history/report projections, and accounting-domain/loan-servicing migration. An SQL-level fixture exercising balanced journals does not establish a live financial-source projection, provider settlement, IFRS9/IFRS17 calculations, reporting compliance, or a complete customer journey.
