/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.controllers.posting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.accounting.core.services.posting.approval.GlFinancialApprovalService;
import io.fireflysoftware.core.accounting.core.services.posting.GlPostingIntentCodec;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerMappingSnapshotRules;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import io.fireflysoftware.core.accounting.models.entities.approval.GlFinancialApprovalControl;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Transport contract only; the mocked owner establishes no approval or posting acceptance. */
class FinancialApprovalsControllerTest {
    private static final String TENANT=UUID.randomUUID().toString(),BOOK=UUID.randomUUID().toString();
    private static final UUID APPROVAL=UUID.randomUUID(),COMMAND=UUID.randomUUID(),SNAPSHOT=UUID.randomUUID();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static FinancialApprovalsController controller(GlFinancialApprovalService... services){
        var beans=new StaticListableBeanFactory();for(int n=0;n<services.length;n++)beans.addBean("owner"+n,services[n]);
        return new FinancialApprovalsController(beans.getBeanProvider(GlFinancialApprovalService.class));
    }
    private static GlFinancialApprovalControl state(String state,long revision){return new GlFinancialApprovalControl(APPROVAL,UUID.fromString(TENANT),UUID.fromString(BOOK),state,revision,
            state.equals("APPROVED")?Instant.now().plusSeconds(60).toEpochMilli():null,null,null,null,null,COMMAND,"a".repeat(64),"b".repeat(64));}
    private static MockServerHttpRequest body(String value){return MockServerHttpRequest.post("/synthetic").contentType(MediaType.APPLICATION_JSON).body(value);}
    private static void forbiddenBody(Runnable run){assertThatThrownBy(run::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(400));}

    @Test void exactEmbeddedPostingBytesAndStableCommandReachOnlyTheMappedOwnerMethod() throws Exception {
        var owner=mock(GlFinancialApprovalService.class);var currency=new GlCurrencyReference(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"USD",1L,"a".repeat(64),"MASTERS_CURRENCY_POLICY_V1");
        var admission=UUID.randomUUID();var leaf1=UUID.randomUUID();var leaf2=UUID.randomUUID();var control=UUID.randomUUID();var mappingId=UUID.randomUUID();
        var source=new AccountBinding(new LedgerAccountReference(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),new PostingLeaf(TENANT,UUID.fromString(BOOK),leaf1,1,"POSTING","LIABILITY",List.of(new ControlReference(control,1))));
        var destination=new AccountBinding(new LedgerAccountReference(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),new PostingLeaf(TENANT,UUID.fromString(BOOK),leaf2,1,"POSTING","LIABILITY",List.of(new ControlReference(control,1))));
        var mapping=new MappingSnapshot(TENANT,UUID.fromString(BOOK),new MappingReference(mappingId,1,"b".repeat(64),MappingPurpose.INTERNAL_CUSTOMER_TRANSFER),source,destination,
                new CurrencyCrosswalk(UUID.randomUUID(),1,"c".repeat(64),"synthetic-policy",2,currency,admission,1));
        long now=Instant.now().toEpochMilli(),until=now+600_000;
        var posting=new GlPostingRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1L,1L,now+3_600_000,"2026-11-01",now,
                new GlSourceReference("LEDGER",UUID.randomUUID().toString(),UUID.randomUUID().toString(),1L,"d".repeat(64),"COMMIT",0,UUID.randomUUID().toString(),UUID.randomUUID().toString(),now),
                mappingId.toString(),1L,"b".repeat(64),"Synthetic transport",List.of(new GlPostingEntry("001","Synthetic",List.of(
                new GlPostingLine("001",leaf1.toString(),1L,admission.toString(),1L,currency,2,"DR","100","Internal customer transfer"),
                new GlPostingLine("002",leaf2.toString(),1L,admission.toString(),1L,currency,2,"CR","100","Internal customer transfer")))));
        var original=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(posting);var bytes=original.getBytes(StandardCharsets.UTF_8);
        when(owner.requestMapped(eq(TENANT),eq(BOOK),eq(APPROVAL),eq(COMMAND),eq(until),aryEq(bytes),eq(mapping))).thenReturn(Mono.just(state("REQUESTED",0)));
        var envelope="{\"commandId\":\""+COMMAND+"\",\"businessUntilEpochMillis\":"+until+",\"originalPosting\":"+original+",\"mappingSnapshot\":"+JSON.writeValueAsString(mapping)+"}";
        var response=controller(owner).request(TENANT,BOOK,APPROVAL,body(envelope)).block(Duration.ofSeconds(2));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().lastCommandId()).isEqualTo(COMMAND);
        var validated=GlLedgerMappingSnapshotRules.validate(mapping);var intent=GlPostingIntentCodec.decode(TENANT,BOOK,bytes).validated();
        var detail=new GlFinancialApprovalDetail(new GlFinancialApprovalReceipt(APPROVAL,"REQUESTED",0,null,COMMAND),until,posting,original,intent.intentDigest(),
                SNAPSHOT,GlLedgerMappingSnapshotRules.FORMAT,mapping,validated.snapshotSha256());
        when(owner.detail(TENANT,BOOK,APPROVAL)).thenReturn(Mono.just(detail));
        var reviewed=controller(owner).current(TENANT,BOOK,APPROVAL).block(Duration.ofSeconds(2));
        assertThat(reviewed.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(reviewed.getBody()).isEqualTo(detail);
        assertThat(reviewed.getBody().originalPostingJson()).isEqualTo(original);assertThat(reviewed.getBody().mappingSnapshot()).isEqualTo(mapping);
        verify(owner).requestMapped(eq(TENANT),eq(BOOK),eq(APPROVAL),eq(COMMAND),eq(until),aryEq(bytes),eq(mapping));verify(owner).detail(TENANT,BOOK,APPROVAL);verifyNoMoreInteractions(owner);
    }
    private static String checkBody(){return "{\"commandId\":\""+COMMAND+"\",\"expectedRevision\":0,\"expectedSnapshotId\":\""+SNAPSHOT
            +"\",\"expectedSnapshotSha256\":\""+"a".repeat(64)+"\",\"expectedIntentDigest\":\""+"b".repeat(64)+"\"}";}
    @Test void transitionKeepsCommandAndExpectedRevisionAndCurrentReadHasNoRequestAuthority() {
        var owner=mock(GlFinancialApprovalService.class);when(owner.checkMapped(TENANT,BOOK,APPROVAL,COMMAND,0,SNAPSHOT,"a".repeat(64),"b".repeat(64))).thenReturn(Mono.just(state("APPROVED",1)));
        when(owner.revoke(TENANT,BOOK,APPROVAL,COMMAND,1)).thenReturn(Mono.just(state("REVOKED",2)));when(owner.detail(TENANT,BOOK,APPROVAL)).thenReturn(Mono.empty());
        var controller=controller(owner);assertThat(controller.check(TENANT,BOOK,APPROVAL,body(checkBody())).block().getBody().state()).isEqualTo("APPROVED");
        assertThat(controller.revoke(TENANT,BOOK,APPROVAL,body("{\"commandId\":\""+COMMAND+"\",\"expectedRevision\":1}")).block().getBody().state()).isEqualTo("REVOKED");
        assertThatThrownBy(()->controller.current(TENANT,BOOK,APPROVAL).block()).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(502));
        verify(owner).checkMapped(TENANT,BOOK,APPROVAL,COMMAND,0,SNAPSHOT,"a".repeat(64),"b".repeat(64));verify(owner).revoke(TENANT,BOOK,APPROVAL,COMMAND,1);verify(owner).detail(TENANT,BOOK,APPROVAL);verifyNoMoreInteractions(owner);
    }
    @Test void unknownAuthorityFieldsDuplicateKeysAndMissingCommandAreRefusedBeforeOwnerCall() {
        var owner=mock(GlFinancialApprovalService.class);var controller=controller(owner);
        var valid=checkBody();
        for(var value:List.of("{}",valid.substring(0,valid.length()-1)+",\"actor\":\"checker\"}",
                "{\"commandId\":\""+COMMAND+"\","+valid.substring(1)))
            forbiddenBody(()->controller.check(TENANT,BOOK,APPROVAL,body(value)).block());
        verifyNoInteractions(owner);
    }
    @Test void missingAmbiguousEmptyOrUnboundOwnerCannotReturnPositiveLifecycleReceipt() {
        for(var controller:List.of(controller(),controller(mock(GlFinancialApprovalService.class),mock(GlFinancialApprovalService.class))))
            assertThatThrownBy(()->controller.current(TENANT,BOOK,APPROVAL).block()).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(503));
        var owner=mock(GlFinancialApprovalService.class);when(owner.detail(TENANT,BOOK,APPROVAL)).thenReturn(Mono.empty());
        assertThatThrownBy(()->controller(owner).current(TENANT,BOOK,APPROVAL).block()).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(502));
    }
}
