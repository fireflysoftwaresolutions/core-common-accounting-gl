/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.*;

/** Actual producer in-process; explicit 3.0 compatibility with the committed 3.0.1 baseline. */
@ActiveProfiles("openapi-gen")
@SpringBootTest(classes = OpenApiGenApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.main.web-application-type=reactive", "firefly.accounting.native-posting.enabled=false",
                "springdoc.api-docs.enabled=true", "springdoc.api-docs.version=OPENAPI_3_0"})
class NativeJournalProducerContractTest {
    static final String BASE = "/api/v1/tenants/{tenantId}/books/{bookId}/postings";
    static final AtomicInteger CAPTURES = new AtomicInteger();
    @Autowired ApplicationContext context;

    JsonNode document() throws Exception {
        byte[] bytes = WebTestClient.bindToApplicationContext(context).configureClient()
                .responseTimeout(Duration.ofSeconds(3)).build().get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertThat(bytes).isNotNull(); assertThat(bytes.length).isLessThanOrEqualTo(4 * 1024 * 1024);
        String supplied = System.getProperty("gl.source.output");
        Path directory = supplied == null ? Path.of("target", "gl-producer").toAbsolutePath() : Path.of(supplied);
        assertThat(directory.isAbsolute()).isTrue();
        if (supplied == null) Files.createDirectories(directory);
        OpenOption[] options = supplied == null
                ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                : new OpenOption[] {StandardOpenOption.CREATE_NEW};
        Path capture = directory.resolve("producer-openapi-" + CAPTURES.incrementAndGet() + ".json");
        Files.write(capture, bytes, options);
        Files.setPosixFilePermissions(capture, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(Path.of(capture + ".sha256"), sha + "\n", options);
        return new ObjectMapper().readTree(bytes);
    }

    static JsonNode resolve(JsonNode document, JsonNode node) {
        if (!node.has("$ref")) return node;
        String reference = node.path("$ref").asText();
        assertThat(reference).startsWith("#/components/schemas/");
        return document.at(reference.substring(1));
    }

    @Test void originalProducerEmitsCompleteTypedJsonInputInsteadOfBinaryOrString() throws Exception {
        var document = document();
        for (String path : List.of(BASE, BASE + "/recovery")) {
            var operation = document.path("paths").path(path).path("post");
            assertThat(operation.isMissingNode()).isFalse();
            var body = operation.path("requestBody"); assertThat(body.path("required").asBoolean()).isTrue();
            var schema = resolve(document, body.path("content").path("application/json").path("schema"));
            assertThat(schema.path("type").asText()).isEqualTo("object");
            var required = new HashSet<String>(); schema.path("required").forEach(value -> required.add(value.asText()));
            assertThat(required).containsExactlyInAnyOrder("actionId", "periodId", "expectedBookVersion",
                    "expectedPeriodVersion", "notAfterEpochMillis", "businessDate", "sourceOccurrenceEpochMillis",
                    "source", "mappingId", "mappingVersion", "mappingDigest", "description", "entries");
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        }
    }

    @Test void originalProducerBindsReceiptErrorsBearerAndDisablesGenericReplayHeader() throws Exception {
        var document = document();
        var bearer = document.path("components").path("securitySchemes").path("glBearer");
        assertThat(bearer.path("type").asText()).isEqualTo("http");
        assertThat(bearer.path("scheme").asText()).isEqualTo("bearer");
        for (String path : List.of(BASE, BASE + "/recovery")) {
            var operation = document.path("paths").path(path).path("post");
            boolean secured = false;
            for (var requirement : operation.path("security")) secured |= requirement.has("glBearer");
            assertThat(secured).isTrue();
            var receipt = resolve(document, operation.path("responses").path("200").path("content")
                    .path("application/json").path("schema"));
            var required = new HashSet<String>(); receipt.path("required").forEach(value -> required.add(value.asText()));
            assertThat(required).contains("receiptId", "actionId", "postingId", "journalSequence", "intentDigest", "recovered", "recordedAt");
            assertThat(receipt.path("properties").path("recordedAt").path("$ref").asText()).isEqualTo("#/components/schemas/FireflyInstant");
            assertThat(document.path("components").path("schemas").path("FireflyInstant").path("type").asText()).isEqualTo("string");
            assertThat(document.path("components").path("schemas").path("FireflyInstant").path("format").asText()).isEqualTo("date-time");
            var error = resolve(document, operation.path("responses").path("503").path("content")
                    .path("application/json").path("schema"));
            assertThat(error.path("properties").has("status")).isTrue();
            assertThat(error.path("properties").has("code")).isTrue();
            assertThat(error.path("properties").has("actionReference")).isTrue();
            assertThat(error.path("properties").has("message")).isFalse();
            for (var parameter : operation.path("parameters"))
                assertThat(parameter.path("name").asText().equalsIgnoreCase("X-Idempotency-Key")).isFalse();
        }
    }

    @Test void originalProducerKeepsExistingControllerRoutesAndCreatesNoBusinessFactory() throws Exception {
        var document = document();
        assertThat(context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class, false, false)).isEmpty();
        var mappings = context.getBean(RequestMappingHandlerMapping.class);
        int retained = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            Class<?> type = entry.getValue().getBeanType();
            if (!type.getPackageName().startsWith("io.fireflysoftware.core.accounting.web.controllers")
                    || type.getSimpleName().equals("NativeJournalPostingsController")) continue;
            for (var pattern : entry.getKey().getPatternsCondition().getPatterns()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    assertThat(document.path("paths").path(pattern.getPatternString())
                            .path(method.name().toLowerCase(Locale.ROOT)).isMissingNode()).isFalse();
                    retained++;
                }
            }
        }
        assertThat(retained).isPositive();
    }
}
