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
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var jar =
                Path.of("../core-common-accounting-gl-web/target/core-common-accounting-gl.jar")
                        .toAbsolutePath();
        app =
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
                                "--spring.flyway.enabled=false",
                                "--firefly.eda.enabled=false",
                                "--firefly.stepevents.enabled=false")
                        .redirectErrorStream(true)
                        .redirectOutput(Path.of("target/legacy-gl-native.log").toFile())
                        .start();
        for (int i = 0; i < 160; i++) {
            if (!app.isAlive()) throw new IllegalStateException("Private legacy GL exited");
            try {
                if (get("/v3/api-docs").statusCode() == 200) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Private legacy GL readiness timeout");
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.destroy();
            if (!app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                app.destroyForcibly();
                assertThat(app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    static ApiClient client() {
        var c = new ApiClient();
        c.setBasePath("http://127.0.0.1:" + port);
        return c;
    }

    static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
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
