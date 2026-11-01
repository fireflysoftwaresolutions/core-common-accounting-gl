/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.openapi;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Captures the real producer as the private reactor SDK input; no handwritten contract replacement. */
@ActiveProfiles("openapi-gen")
@SpringBootTest(classes=OpenApiGenApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK,
        properties={"spring.main.web-application-type=reactive","firefly.accounting.native-posting.enabled=false",
                "springdoc.api-docs.enabled=true","springdoc.api-docs.version=OPENAPI_3_0"})
class FinancialApprovalProducerContractTest {
    @Autowired ApplicationContext context;
    @Test void actualProducerDefinesFourProtectedLifecycleOperationsAndFullTypedProposal() throws Exception {
        var body=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build()
                .get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertThat(body).isNotNull();assertThat(body.length).isLessThanOrEqualTo(4*1024*1024);
        var document=new ObjectMapper().readTree(body);
        var raw=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build()
                .get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertThat(raw).isNotNull();assertThat(raw.length).isLessThanOrEqualTo(4*1024*1024);
        var output=Path.of("target","gl-producer","financial-approval-producer.yml");Files.createDirectories(output.getParent());
        Files.write(output,raw);Files.setPosixFilePermissions(output,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(new ObjectMapper(new YAMLFactory()).readTree(raw).path("paths")).isEqualTo(document.path("paths"));
        String root="/api/v1/tenants/{tenantId}/books/{bookId}/financial-approvals/{approvalId}";
        for(var pair:List.of(List.of(root,"post","requestFinancialApproval"),List.of(root+"/check","post","checkFinancialApproval"),
                List.of(root+"/revoke","post","revokeFinancialApproval"),List.of(root,"get","readFinancialApproval"))){
            var operation=document.path("paths").path(pair.get(0)).path(pair.get(1));assertThat(operation.path("operationId").asText()).isEqualTo(pair.get(2));
            boolean secured=false;for(var requirement:operation.path("security"))secured|=requirement.has("glBearer");assertThat(secured).isTrue();
            for(var parameter:operation.path("parameters"))assertThat(parameter.path("name").asText()).isNotEqualToIgnoringCase("X-Idempotency-Key");
            var receipt=NativeJournalProducerContractTest.resolve(document,operation.path("responses").path("200").path("content").path("application/json").path("schema"));
            if(pair.get(1).equals("get")){
                for(var field:List.of("approval","originalPosting","originalPostingJson","intentDigest","snapshotId","snapshotFormat","mappingSnapshot","snapshotSha256"))
                    assertThat(receipt.path("properties").has(field)).isTrue();
            }else{assertThat(receipt.path("properties").has("approvalId")).isTrue();assertThat(receipt.path("properties").has("lastCommandId")).isTrue();}
        }
        var schema=NativeJournalProducerContractTest.resolve(document,document.path("paths").path(root).path("post").path("requestBody").path("content").path("application/json").path("schema"));
        var required=new HashSet<String>();schema.path("required").forEach(value->required.add(value.asText()));
        assertThat(required).containsExactlyInAnyOrder("commandId","businessUntilEpochMillis","originalPosting","mappingSnapshot");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        var mapping=NativeJournalProducerContractTest.resolve(document,schema.path("properties").path("mappingSnapshot"));
        for(var field:List.of("tenant","bookId","reference","source","destination","currency"))assertThat(mapping.path("properties").has(field)).isTrue();
        var check=NativeJournalProducerContractTest.resolve(document,document.path("paths").path(root+"/check").path("post").path("requestBody").path("content").path("application/json").path("schema"));
        var reviewRequired=new HashSet<String>();check.path("required").forEach(value->reviewRequired.add(value.asText()));
        assertThat(reviewRequired).containsExactlyInAnyOrder("commandId","expectedRevision","expectedSnapshotId","expectedSnapshotSha256","expectedIntentDigest");
        assertThat(document.path("paths").has(NativeJournalProducerContractTest.BASE)).isTrue();
        assertThat(document.path("paths").has(NativeJournalProducerContractTest.BASE+"/recovery")).isTrue();
        assertThat(context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class,false,false)).isEmpty();
        OriginalProducerSdkInputFixtureImpl.writeIfRequested(raw);

    }
}
