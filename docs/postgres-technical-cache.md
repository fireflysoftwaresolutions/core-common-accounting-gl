# PostgreSQL technical cache deployment

GL keeps POSTGRES as its cache provider. The Web module includes the managed
`org.fireflyframework:fireflyframework-cache-postgres` dependency. Its framework-owned
`fireflyCachePostgresConnectionFactory` is lazy, non-default, and configured only by
`firefly.cache.postgres.*`; it does not infer or reuse `spring.r2dbc.*` or `DB_*`.

Provide `CACHE_DB_HOST`, `CACHE_DB_PORT`, `CACHE_DB_NAME`, `CACHE_DB_USERNAME`,
`CACHE_DB_PASSWORD`, `CACHE_DB_SSL_MODE`, and `CACHE_DB_SCHEMA` through deployment
configuration. Database and username have no business-database fallback. TLS defaults
to `verify-full`; isolated loopback fixtures explicitly select `disable`.

Provision a dedicated technical database and login before startup. The login needs
CONNECT on that database, USAGE on its cache schema, and SELECT/INSERT/UPDATE/DELETE
on the cache table. It must not own the database/schema/table, create objects,
manage roles, bypass RLS, or connect to the business database. The business login
must not connect to the technical database. Revoke PUBLIC database CONNECT and schema
CREATE where necessary, then grant only the intended principals. Use a separate
administrative installer; never give the runtime cache login installer privileges.

The default technical schema is `firefly_gl_cache`; the table is `cache_entries`:

```sql
CREATE TABLE firefly_gl_cache.cache_entries (
    cache_name VARCHAR(255) NOT NULL,
    cache_key VARCHAR(1024) NOT NULL,
    value BYTEA NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (cache_name, cache_key)
);
CREATE INDEX cache_entries_expiry ON firefly_gl_cache.cache_entries (expires_at)
    WHERE expires_at IS NOT NULL;
```

The provider performs a real storage check and fails startup if the selected pool or
table cannot be used. `auto-create-schema` remains false. The pool is bounded to
1–2 connections, with 5-second connect/acquire/shutdown bounds and a 15-second
initialization bound. Provisioning these technical objects does not activate native
financial posting, grant any accounting approval, or create business authority.

The maintained Legacy safety fixture provisions this separation in its newly owned
PostgreSQL container, passes only the technical settings to the original executable
Main, and checks role privileges, cross-database refusal, and the actual cache login.
These source additions are pending fresh hosted qualification. Earlier native runs
and their startup failures remain unchanged; no deployment acceptance transfers.
