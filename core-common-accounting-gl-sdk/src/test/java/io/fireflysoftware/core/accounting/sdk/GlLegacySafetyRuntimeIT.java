/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fireflysoftware.core.accounting.sdk;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fireflysoftware.core.accounting.invoker.ApiClient;
import io.fireflysoftware.core.accounting.sdk.api.JournalBatchesApi;
import io.fireflysoftware.core.accounting.sdk.api.JournalLinesApi;
import io.fireflysoftware.core.accounting.sdk.model.JournalBatchDTO;
import io.fireflysoftware.core.accounting.sdk.impl.GlLegacySignedAuthorityFixtureImpl;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;

@Testcontainers
class GlLegacySafetyRuntimeIT {
    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("synthetic_gl_legacy")
                    .withUsername("gl_owner")
                    .withPassword("synthetic-only")
                    .withCreateContainerCmdModifier(
                            c ->
                                    c.getHostConfig()
                                            .withPortBindings(
                                                    new com.github.dockerjava.api.model.PortBinding(
                                                            com.github.dockerjava.api.model.Ports
                                                                    .Binding.bindIp("127.0.0.1"),
                                                            new com.github.dockerjava.api.model
                                                                    .ExposedPort(5432))));

    static final UUID A = UUID.randomUUID(),
            B = UUID.randomUUID(),
            ENTRY = UUID.randomUUID(),
            LINE = UUID.randomUUID(),
            ACCOUNT = UUID.randomUUID(),
            HISTORICAL_BATCH = UUID.randomUUID(),
            HISTORICAL_ENTRY = UUID.randomUUID(),
            HISTORICAL_LINE = UUID.randomUUID();
    static final Duration WAIT = Duration.ofSeconds(15);
    static Process app;
    static int port;
    static String cachePassword;
    static GlLegacySignedAuthorityFixtureImpl signedAuthority;
    static String fixtureBearer;

    static void owner(String sql) throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate();
        owner(
                "INSERT INTO journal_batch(id,reference,status,batch_date) VALUES('"
                        + HISTORICAL_BATCH
                        + "','historical-unverified','POSTED','2026-10-20');"
                        + " INSERT INTO gl_account(id,code,name,type,is_leaf,currency) VALUES('"
                        + ACCOUNT
                        + "','SYNTHETIC','Private draft fixture','ASSET',true,'USD'); INSERT INTO"
                        + " journal_entry(id,batch_id,total_amount,entry_date,posted) VALUES('"
                        + HISTORICAL_ENTRY
                        + "','"
                        + HISTORICAL_BATCH
                        + "',1,'2026-10-20',true);"
                        + " INSERT INTO journal_line(id,entry_id,account_id,amount,dr_cr) VALUES('"
                        + HISTORICAL_LINE
                        + "','"
                        + HISTORICAL_ENTRY
                        + "','"
                        + ACCOUNT
                        + "',1,'DR');");
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner(
                "CREATE ROLE gl_legacy_app LOGIN PASSWORD 'synthetic-only' NOSUPERUSER NOBYPASSRLS;"
                    + " REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO"
                    + " gl_legacy_app; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA"
                    + " public TO gl_legacy_app; INSERT INTO"
                    + " journal_batch(id,reference,status,batch_date) VALUES('"
                        + A
                        + "','synthetic-A','OPEN','2026-10-21'),('"
                        + B
                        + "','synthetic-B','OPEN','2026-10-21'); INSERT INTO"
                        + " journal_entry(id,batch_id,total_amount,entry_date,posted) VALUES('"
                        + ENTRY
                        + "','"
                        + A
                        + "',1,'2026-10-21',false);"
                        + " INSERT INTO journal_line(id,entry_id,account_id,amount,dr_cr) VALUES('"
                        + LINE
                        + "','"
                        + ENTRY
                        + "','"
                        + ACCOUNT
                        + "',1,'DR');");
        byte[] cachePasswordBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(cachePasswordBytes);
        cachePassword = Base64.getUrlEncoder().withoutPadding().encodeToString(cachePasswordBytes);
        owner("CREATE ROLE gl_cache_app LOGIN PASSWORD '" + cachePassword + "' NOSUPERUSER"
                + " NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;"
                + " REVOKE CONNECT ON DATABASE synthetic_gl_legacy FROM PUBLIC;"
                + " GRANT CONNECT ON DATABASE synthetic_gl_legacy TO gl_legacy_app");
        // CREATE DATABASE cannot run in the multi-statement transaction above.
        owner("CREATE DATABASE synthetic_gl_cache");
        try (var connection = DriverManager.getConnection(cacheJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()); var statement = connection.createStatement()) {
            statement.execute("REVOKE CONNECT ON DATABASE synthetic_gl_cache FROM PUBLIC;"
                    + " GRANT CONNECT ON DATABASE synthetic_gl_cache TO gl_cache_app;"
                    + " REVOKE CREATE ON SCHEMA public FROM PUBLIC;"
                    + " CREATE SCHEMA firefly_gl_cache;"
                    + " CREATE TABLE firefly_gl_cache.cache_entries (cache_name VARCHAR(255) NOT NULL,"
                    + " cache_key VARCHAR(1024) NOT NULL, value BYTEA NOT NULL, expires_at TIMESTAMPTZ,"
                    + " created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(cache_name,cache_key));"
                    + " CREATE INDEX cache_entries_expiry ON firefly_gl_cache.cache_entries(expires_at)"
                    + " WHERE expires_at IS NOT NULL;"
                    + " GRANT USAGE ON SCHEMA firefly_gl_cache TO gl_cache_app;"
                    + " GRANT SELECT,INSERT,UPDATE,DELETE ON firefly_gl_cache.cache_entries TO gl_cache_app");
        }
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        signedAuthority = new GlLegacySignedAuthorityFixtureImpl();
        fixtureBearer = signedAuthority.token();
        var jar =
                Path.of("../core-common-accounting-gl-web/target/core-common-accounting-gl.jar")
                        .toAbsolutePath();
        var appBuilder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-Xmx384m",
                                "-jar",
                                jar.toString(),
                                "--server.address=127.0.0.1",
                                "--server.port=" + port,
                                "--DB_HOST=" + postgres.getHost(),
                                "--DB_PORT=" + postgres.getMappedPort(5432),
                                "--DB_NAME=" + postgres.getDatabaseName(),
                                "--DB_USERNAME=gl_legacy_app",
                                "--DB_PASSWORD=synthetic-only",
                                "--DB_SSL_MODE=disable",
                                "--CACHE_DB_HOST=" + postgres.getHost(),
                                "--CACHE_DB_PORT=" + postgres.getMappedPort(5432),
                                "--CACHE_DB_NAME=synthetic_gl_cache",
                                "--CACHE_DB_USERNAME=gl_cache_app",
                                "--CACHE_DB_SSL_MODE=disable",
                                "--CACHE_DB_SCHEMA=firefly_gl_cache",
                                "--firefly.security.resource-server.strict-policy=true",
                                "--firefly.security.resource-server.issuer=" + signedAuthority.issuer(),
                                "--firefly.security.resource-server.jwk-set-uri=" + signedAuthority.baseUrl() + "/jwks",
                                "--firefly.security.resource-server.audiences=" + GlLegacySignedAuthorityFixtureImpl.AUDIENCE,
                                "--firefly.security.resource-server.tenant-claim-paths=tenant",
                                "--firefly.security.resource-server.tenant-required=true",
                                "--firefly.security.resource-server.scope-claim-paths=scope",
                                "--firefly.accounting.authorization.opa.enabled=true",
                                "--firefly.accounting.authorization.opa.base-url=" + signedAuthority.baseUrl(),
                                "--firefly.accounting.authorization.opa.allow-loopback-http=true",
                                "--firefly.accounting.authorization.opa.decision-path=gl-legacy/allow",
                                "--firefly.accounting.authorization.opa.evaluation-path=gl-legacy/current",
                                "--spring.flyway.enabled=false",
                                "--firefly.eda.enabled=false",
                                "--firefly.stepevents.enabled=false")
                        .redirectErrorStream(true)
                        .redirectOutput(Path.of("target/legacy-gl-native.log").toFile());
        appBuilder.environment().put("CACHE_DB_PASSWORD", cachePassword);
        app = appBuilder.start();
        var statuses = new TreeMap<Integer, Integer>();
        var transportErrors = new TreeMap<String, Integer>();
        int lastStatus = -1, lastBytes = 0;
        String lastDigest = "";
        for (int i = 0; i < 160; i++) {
            if (!app.isAlive()) {
                readiness(i, statuses, transportErrors, lastStatus, lastBytes, lastDigest, "CHILD_EXITED");
                throw new IllegalStateException("Private legacy GL exited");
            }
            try {
                var response = documentation(fixtureBearer);
                lastStatus = response.statusCode();lastBytes = response.body().length;
                lastDigest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()));
                statuses.merge(lastStatus, 1, Integer::sum);
                readiness(i + 1, statuses, transportErrors, lastStatus, lastBytes, lastDigest,
                        lastStatus == 200 ? "READY" : "POLLING");
                if (lastStatus == 200) return;
            } catch (java.io.IOException | InterruptedException error) {
                transportErrors.merge(error.getClass().getName(), 1, Integer::sum);
                readiness(i + 1, statuses, transportErrors, lastStatus, lastBytes, lastDigest, "POLLING");
                if (error instanceof InterruptedException) {Thread.currentThread().interrupt();throw error;}
            }
            Thread.sleep(250);
        }
        readiness(160, statuses, transportErrors, lastStatus, lastBytes, lastDigest, "TIMEOUT");
        throw new IllegalStateException("Private legacy GL readiness timeout");
    }

    static void readiness(int attempts, Map<Integer, Integer> statuses, Map<String, Integer> errors,
            int lastStatus, int lastBytes, String lastDigest, String outcome) throws Exception {
        Files.writeString(Path.of("target/legacy-gl-readiness.json"), new ObjectMapper().writeValueAsString(Map.of(
                "schema", "GL_LEGACY_READINESS_V1", "attempts", attempts, "maximumAttempts", 160,
                "statusCounts", statuses, "transportErrorCounts", errors, "lastStatus", lastStatus,
                "lastBodyBytes", lastBytes, "lastBodySha256", lastDigest, "outcome", outcome)) + "\n");
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (app != null) {
                app.destroy();
                if (!app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    app.destroyForcibly();
                    assertThat(app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        } finally {
            if (signedAuthority != null) signedAuthority.close();
        }
    }

    static ApiClient client() {
        var c = new ApiClient();
        c.setBasePath("http://127.0.0.1:" + port);
        c.addDefaultHeader("Authorization", "Bearer " + fixtureBearer);
        return c;
    }

    static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + fixtureBearer)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    static String cacheJdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/synthetic_gl_cache";
    }

    static HttpResponse<byte[]> documentation(String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void originalMainRequiresSignedIssuerAudienceTenantAndScopedPolicy() throws Exception {
        var probes = new TreeMap<String, HttpResponse<byte[]>>();
        probes.put("missing", documentation(null));
        probes.put("badSignature", documentation(signedAuthority.invalidSignature()));
        probes.put("wrongIssuer", documentation(signedAuthority.token("https://untrusted.synthetic.invalid",
                GlLegacySignedAuthorityFixtureImpl.AUDIENCE, signedAuthority.tenant(), GlLegacySignedAuthorityFixtureImpl.SCOPE)));
        probes.put("wrongAudience", documentation(signedAuthority.token(signedAuthority.issuer(), "synthetic-wrong-audience",
                signedAuthority.tenant(), GlLegacySignedAuthorityFixtureImpl.SCOPE)));
        probes.put("wrongTenant", documentation(signedAuthority.token(signedAuthority.issuer(), GlLegacySignedAuthorityFixtureImpl.AUDIENCE,
                UUID.randomUUID().toString(), GlLegacySignedAuthorityFixtureImpl.SCOPE)));
        probes.put("wrongScope", documentation(signedAuthority.token(signedAuthority.issuer(), GlLegacySignedAuthorityFixtureImpl.AUDIENCE,
                signedAuthority.tenant(), "gl:unrelated")));
        var receipts = new TreeMap<String, Object>();
        for (var entry : probes.entrySet()) {
            var response = entry.getValue();
            receipts.put(entry.getKey(), Map.of("status", response.statusCode(), "bodyBytes", response.body().length,
                    "bodySha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()))));
        }
        Files.writeString(Path.of("target/legacy-gl-auth-proof.json"), new ObjectMapper().writeValueAsString(
                Map.of("authority", signedAuthority.proof(), "negativeResponses", receipts)) + "\n");
        for (var response : probes.values()) assertThat(response.statusCode()).isIn(401, 403);
        assertThat(probes.get("wrongScope").statusCode()).isEqualTo(403);
        assertThat(probes.get("wrongTenant").statusCode()).isEqualTo(403);
        assertThat(signedAuthority.proof().get("jwksRequests")).isInstanceOfSatisfying(Integer.class, count -> assertThat(count).isGreaterThan(0));
        assertThat(signedAuthority.proof().get("policyPermits")).isInstanceOfSatisfying(Integer.class, count -> assertThat(count).isGreaterThan(0));
        assertThat(documentation(fixtureBearer).statusCode()).isEqualTo(200);
    }

    @Test
    void technicalCacheUsesItsOwnRestrictedDatabaseAndLogin() throws Exception {
        try (var connection = DriverManager.getConnection(cacheJdbcUrl(),
                "gl_cache_app", cachePassword); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT current_database(), current_user,"
                        + " has_schema_privilege(current_user,'firefly_gl_cache','USAGE'),"
                        + " has_schema_privilege(current_user,'firefly_gl_cache','CREATE'),"
                        + " (has_table_privilege(current_user,'firefly_gl_cache.cache_entries','SELECT')"
                        + " AND has_table_privilege(current_user,'firefly_gl_cache.cache_entries','INSERT')"
                        + " AND has_table_privilege(current_user,'firefly_gl_cache.cache_entries','UPDATE')"
                        + " AND has_table_privilege(current_user,'firefly_gl_cache.cache_entries','DELETE')),"
                        + " rolsuper, rolbypassrls, rolcreatedb, rolcreaterole,"
                        + " (SELECT pg_get_userbyid(relowner) <> current_user FROM pg_class"
                        + " WHERE oid='firefly_gl_cache.cache_entries'::regclass),"
                        + " has_table_privilege(current_user,'firefly_gl_cache.cache_entries','TRUNCATE')"
                        + " FROM pg_roles WHERE rolname=current_user")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("synthetic_gl_cache");
            assertThat(rows.getString(2)).isEqualTo("gl_cache_app");
            assertThat(rows.getBoolean(3)).isTrue();
            assertThat(rows.getBoolean(4)).isFalse();
            assertThat(rows.getBoolean(5)).isTrue();
            for (int column = 6; column <= 9; column++) assertThat(rows.getBoolean(column)).isFalse();
            assertThat(rows.getBoolean(10)).isTrue();
            assertThat(rows.getBoolean(11)).isFalse();
            assertThat(rows.next()).isFalse();
        }
        for (var denied : List.of(
                new String[] {cacheJdbcUrl(), "gl_legacy_app", "synthetic-only"},
                new String[] {postgres.getJdbcUrl(), "gl_cache_app", cachePassword})) {
            assertThatThrownBy(() -> {
                try (var ignored = DriverManager.getConnection(denied[0], denied[1], denied[2])) {
                    fail("Cross-purpose database connection must be refused");
                }
            }).isInstanceOfSatisfying(java.sql.SQLException.class,
                    error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity"
                        + " WHERE datname='synthetic_gl_cache' AND usename='gl_cache_app'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isGreaterThan(0);
        }
    }

    @Test
    void mappedGuardProjectionsRetainHistoricalFactsWithInvokerPrivileges() throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), "gl_legacy_app", "synthetic-only")) {
            try (var query = connection.prepareStatement(
                    "SELECT status, posted FROM public.gl_legacy_journal_batch_state WHERE id=?")) {
                query.setObject(1, HISTORICAL_BATCH);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("POSTED");
                    assertThat(rows.getBoolean("posted")).isTrue();
                    assertThat(rows.next()).isFalse();
                }
            }
            try (var query = connection.prepareStatement(
                    "SELECT batch_id, status, posted FROM public.gl_legacy_journal_entry_state WHERE id=?")) {
                query.setObject(1, HISTORICAL_ENTRY);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("batch_id", UUID.class)).isEqualTo(HISTORICAL_BATCH);
                    assertThat(rows.getString("status")).isEqualTo("POSTED");
                    assertThat(rows.getBoolean("posted")).isTrue();
                }
            }
            try (var query = connection.prepareStatement(
                    "SELECT historical FROM public.gl_legacy_account_usage WHERE id=?")) {
                query.setObject(1, ACCOUNT);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean("historical")).isTrue();
                }
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                    "SELECT reloptions FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                    + "WHERE n.nspname='public' AND c.relname IN ('gl_legacy_journal_batch_state',"
                    + "'gl_legacy_journal_entry_state','gl_legacy_account_usage') ORDER BY c.relname")) {
                int views = 0;
                while (rows.next()) {
                    assertThat(rows.getString(1)).contains("security_invoker=true");
                    views++;
                }
                assertThat(views).isEqualTo(3);
            }
        }
        var historical = new JournalLinesApi(client())
                .getById2(HISTORICAL_BATCH, HISTORICAL_ENTRY, HISTORICAL_LINE, null).block(WAIT);
        assertThat(historical).isNotNull();
        assertThat(historical.getId()).isEqualTo(HISTORICAL_LINE);
    }

    @Test
    void legacyOpenDraftCannotBecomePostedWithAnIncompleteJournal() throws Exception {
        var body =
                new JournalBatchDTO()
                        .reference("synthetic-A")
                        .batchDate(LocalDate.of(2026, 10, 21))
                        .status(JournalBatchDTO.StatusEnum.POSTED);
        assertThatThrownBy(
                        () ->
                                new JournalBatchesApi(client())
                                        .updateWithHttpInfo(A, body, null)
                                        .block(WAIT))
                .isInstanceOfSatisfying(
                        WebClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        try (var c =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var q = c.createStatement();
                var rows =
                        q.executeQuery(
                                "SELECT status::text FROM journal_batch WHERE id='" + A + "'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("OPEN");
        }
    }

    @Test
    void lineReadMustEnforceItsActualBatchParent() {
        assertThatThrownBy(
                        () ->
                                new JournalLinesApi(client())
                                        .getById2(B, ENTRY, LINE, null)
                                        .block(WAIT))
                .isInstanceOfSatisfying(
                        WebClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void entrySearchMustRemainInsideItsBatch() throws Exception {
        var emptyBatch = UUID.randomUUID();
        owner(
                "INSERT INTO journal_batch(id,reference,status,batch_date) VALUES('"
                        + emptyBatch
                        + "','empty-scope','OPEN','2026-10-21')");
        var result =
                get(
                        "/api/v1/journal-batches/"
                                + emptyBatch
                                + "/entries/?pagination.pageNumber=0&pagination.pageSize=20");
        assertThat(result.statusCode()).isEqualTo(200);
        var content = new ObjectMapper().readTree(result.body()).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).isEmpty();
        var own =
                get(
                        "/api/v1/journal-batches/"
                                + A
                                + "/entries/?pagination.pageNumber=0&pagination.pageSize=20&filters.batchId="
                                + HISTORICAL_BATCH);
        assertThat(own.statusCode()).isEqualTo(200);
        var ownContent = new ObjectMapper().readTree(own.body()).path("content");
        assertThat(ownContent).isNotEmpty();
        for (var entry : ownContent)
            assertThat(entry.path("batchId").asText()).isEqualTo(A.toString());
    }

    static HttpResponse<String> request(String method, String path, String json) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + fixtureBearer)
                        .header("Content-Type", "application/json");
        return HttpClient.newHttpClient()
                .send(
                        builder.method(
                                        method,
                                        json == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    static void deniedSql(String sql) {
        assertThatThrownBy(
                        () -> {
                            try (var c =
                                            DriverManager.getConnection(
                                                    postgres.getJdbcUrl(),
                                                    "gl_legacy_app",
                                                    "synthetic-only");
                                    var q = c.createStatement()) {
                                q.execute(sql);
                            }
                        })
                .isInstanceOfSatisfying(
                        java.sql.SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void rawSqlCannotCreateOrFinalizeLegacyFinancialState() {
        for (var status : List.of("POSTED", "REVERSED")) {
            deniedSql(
                    "INSERT INTO journal_batch(id,reference,status,batch_date) VALUES('"
                            + UUID.randomUUID()
                            + "','forbidden','"
                            + status
                            + "','2026-10-21')");
            deniedSql("UPDATE journal_batch SET status='" + status + "' WHERE id='" + A + "'");
        }
        deniedSql("UPDATE journal_entry SET posted=true WHERE id='" + ENTRY + "'");
        deniedSql(
                "INSERT INTO journal_entry(id,batch_id,total_amount,entry_date,posted) VALUES('"
                        + UUID.randomUUID()
                        + "','"
                        + A
                        + "',1,'2026-10-21',true)");
    }

    @Test
    void historicalPostedRowsRemainImmutableWithoutInventingBalance() throws Exception {
        for (var sql :
                List.of(
                        "UPDATE journal_batch SET remarks='changed' WHERE id='"
                                + HISTORICAL_BATCH
                                + "'",
                        "DELETE FROM journal_batch WHERE id='" + HISTORICAL_BATCH + "'",
                        "UPDATE journal_entry SET posted=false WHERE id='" + HISTORICAL_ENTRY + "'",
                        "DELETE FROM journal_entry WHERE id='" + HISTORICAL_ENTRY + "'",
                        "UPDATE journal_line SET amount=2 WHERE id='" + HISTORICAL_LINE + "'",
                        "DELETE FROM journal_line WHERE id='" + HISTORICAL_LINE + "'"))
            deniedSql(sql);
        assertThat(
                        request(
                                        "PUT",
                                        "/api/v1/journal-batches/" + HISTORICAL_BATCH,
                                        "{\"reference\":\"changed\",\"status\":\"OPEN\",\"batchDate\":\"2026-10-20\"}")
                                .statusCode())
                .isEqualTo(409);
        assertThat(get("/api/v1/journal-batches/" + HISTORICAL_BATCH).statusCode()).isEqualTo(200);
    }

    @Test
    void allNestedLineOperationsRejectTheWrongBatchBeforeEffects() throws Exception {
        var entry = UUID.randomUUID();
        var line = UUID.randomUUID();
        owner(
                "INSERT INTO journal_entry(id,batch_id,total_amount,entry_date) VALUES('"
                        + entry
                        + "','"
                        + A
                        + "',1,'2026-10-21'); INSERT INTO"
                        + " journal_line(id,entry_id,account_id,amount,dr_cr) VALUES('"
                        + line
                        + "','"
                        + entry
                        + "','"
                        + ACCOUNT
                        + "',1,'DR')");
        var path = "/api/v1/journal-batches/" + B + "/entries/" + entry + "/lines";
        var body = "{\"accountId\":\"" + ACCOUNT + "\",\"amount\":2,\"drCr\":\"CR\"}";
        assertThat(request("PUT", path + "/" + line, body).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", path + "/" + line, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", path, body).statusCode()).isEqualTo(404);
        assertThat(
                        request(
                                        "GET",
                                        path + "/",
                                        "{\"filters\":{},\"pagination\":{\"pageNumber\":0,\"pageSize\":20}}")
                                .statusCode())
                .isEqualTo(404);
        var retained = get("/api/v1/journal-batches/" + A + "/entries/" + entry + "/lines/" + line);
        assertThat(retained.statusCode()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(retained.body()).path("amount").decimalValue())
                .isEqualByComparingTo("1");
    }

    @Test
    void directEntryAndLineParentRetargetingCannotMoveExistingRows() throws Exception {
        var otherEntry = UUID.randomUUID();
        owner(
                "INSERT INTO journal_entry(id,batch_id,total_amount,entry_date) VALUES('"
                        + otherEntry
                        + "','"
                        + B
                        + "',1,'2026-10-21')");
        deniedSql("UPDATE journal_entry SET batch_id='" + B + "' WHERE id='" + ENTRY + "'");
        deniedSql("UPDATE journal_line SET entry_id='" + otherEntry + "' WHERE id='" + LINE + "'");
    }

    @Test
    void legacyOpenDraftCreateUpdateAndDeleteRetainWorkingBehavior() throws Exception {
        var batch =
                request(
                        "POST",
                        "/api/v1/journal-batches",
                        "{\"reference\":\"draft-roundtrip\",\"status\":\"OPEN\",\"batchDate\":\"2026-10-21\"}");
        assertThat(batch.statusCode()).isBetween(200, 201);
        var batchId = new ObjectMapper().readTree(batch.body()).path("id").asText();
        var entry =
                request(
                        "POST",
                        "/api/v1/journal-batches/" + batchId + "/entries",
                        "{\"totalAmount\":1,\"entryDate\":\"2026-10-21T00:00:00\",\"posted\":false}");
        assertThat(entry.statusCode()).isEqualTo(201);
        var entryId = new ObjectMapper().readTree(entry.body()).path("id").asText();
        var line =
                request(
                        "POST",
                        "/api/v1/journal-batches/" + batchId + "/entries/" + entryId + "/lines",
                        "{\"accountId\":\"" + ACCOUNT + "\",\"amount\":1,\"drCr\":\"DR\"}");
        assertThat(line.statusCode()).isEqualTo(201);
        var lineId = new ObjectMapper().readTree(line.body()).path("id").asText();
        assertThat(
                        request(
                                        "DELETE",
                                        "/api/v1/journal-batches/"
                                                + batchId
                                                + "/entries/"
                                                + entryId
                                                + "/lines/"
                                                + lineId,
                                        null)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        request(
                                        "DELETE",
                                        "/api/v1/journal-batches/"
                                                + batchId
                                                + "/entries/"
                                                + entryId,
                                        null)
                                .statusCode())
                .isEqualTo(204);
        assertThat(request("DELETE", "/api/v1/journal-batches/" + batchId, null).statusCode())
                .isEqualTo(204);
        assertThat(request("DELETE", "/api/v1/journal-batches/" + batchId, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void transportReplayCannotSkipCurrentDraftSafetyChecks() throws Exception {
        var key = UUID.randomUUID().toString();
        var client = HttpClient.newHttpClient();
        var path = URI.create("http://127.0.0.1:" + port + "/api/v1/journal-batches");
        for (var status : List.of("OPEN", "POSTED")) {
            var body =
                    "{\"reference\":\"warm-draft-key\",\"status\":\""
                            + status
                            + "\",\"batchDate\":\"2026-10-21\"}";
            var response =
                    client.send(
                            HttpRequest.newBuilder(path)
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + fixtureBearer)
                                    .header("X-Idempotency-Key", key)
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(status.equals("OPEN") ? 200 : 409);
        }
    }

    @Test
    void chartIdentityUsedByHistoricalPostingsCannotBeReinterpreted() throws Exception {
        deniedSql("UPDATE gl_account SET currency='EUR' WHERE id='" + ACCOUNT + "'");
        deniedSql("UPDATE gl_account SET type='INCOME' WHERE id='" + ACCOUNT + "'");
        deniedSql("UPDATE gl_account SET code='REPLACED' WHERE id='" + ACCOUNT + "'");
        assertThat(
                        request(
                                        "PUT",
                                        "/api/v1/gl-accounts/" + ACCOUNT,
                                        "{\"code\":\"REPLACED\",\"name\":\"Changed\",\"type\":\"INCOME\",\"leaf\":true,\"active\":true,\"currency\":\"EUR\"}")
                                .statusCode())
                .isEqualTo(409);
    }

    @Test
    void generatedAccountClientRetainsItsPublishedLeafWireContract() {
        var api = new io.fireflysoftware.core.accounting.sdk.api.GlAccountsApi(client());
        var body =
                new io.fireflysoftware.core.accounting.sdk.model.GlAccountDTO()
                        .code("SYNTHETIC-LEAF")
                        .name("Synthetic unused chart node")
                        .type(
                                io.fireflysoftware.core.accounting.sdk.model.GlAccountDTO.TypeEnum
                                        .ASSET)
                        .leaf(true)
                        .active(true)
                        .currency("USD");
        var created = api.create3WithHttpInfo(body, null).block(WAIT);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        var id = created.getBody().getId();
        assertThat(id).isNotNull();
        assertThat(created.getBody().getLeaf()).isTrue();
        assertThat(api.getById3(id, null).block(WAIT).getLeaf()).isTrue();
        body.leaf(false);
        assertThat(api.update3(id, body, null).block(WAIT).getLeaf()).isFalse();
        api.delete3(id, null).block(WAIT);
        assertThatThrownBy(() -> api.getById3(id, null).block(WAIT))
                .isInstanceOfSatisfying(
                        WebClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void managedChartPrivacyAndLegacyMoneyFencesApplyThroughTheActualNonownerApplication() throws Exception {
        UUID managed;
        try (var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            var scope=GlNativeAccountingSchemaIT.book(c);
            managed=GlNativeAccountingSchemaIT.chart(c,scope,"POSTING",null);
        }
        var api=new io.fireflysoftware.core.accounting.sdk.api.GlAccountsApi(client());
        assertThatThrownBy(() -> api.getById3(managed,null).block(WAIT))
                .isInstanceOfSatisfying(WebClientResponseException.class,error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        var response=request("POST","/api/v1/journal-batches/"+A+"/entries/"+ENTRY+"/lines",
                "{\"accountId\":\""+managed+"\",\"amount\":1,\"drCr\":\"DR\",\"memo\":\"Synthetic managed fence\"}");
        assertThat(response.statusCode()).isEqualTo(409);
        try (var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM journal_line WHERE account_id='"+managed+"'")) {
            r.next();assertThat(r.getInt(1)).isZero();
        }
    }
}
