/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.controllers.posting;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.fireflysoftware.core.accounting.core.services.posting.GlPostingRules;
import io.fireflysoftware.core.accounting.core.services.posting.GlPostingIntentCodec;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.GlLedgerMappingSnapshotRules;
import io.fireflysoftware.core.accounting.core.services.posting.approval.GlFinancialApprovalService;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.approval.*;
import io.fireflysoftware.core.accounting.models.entities.approval.GlFinancialApprovalControl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Current identity and all business authority remain inside the same owner service. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/books/{bookId}/financial-approvals/{approvalId}")
@Tag(name="FinancialApprovals",description="Exact retained maker/checker lifecycle; approval alone does not execute a journal")
@SecurityRequirement(name="glBearer")
@ApiResponses({@ApiResponse(responseCode="400",description="Complete bounded original command required"),
        @ApiResponse(responseCode="401",description="Current bearer authentication required"),
        @ApiResponse(responseCode="403",description="Current scoped owner policy and independent staff identity required"),
        @ApiResponse(responseCode="503",description="Unobserved completion: retain the original approval and command identities; read current state before recovery")})
public class FinancialApprovalsController {
    private static final int MAX_BYTES=1_048_576;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    private final ObjectProvider<GlFinancialApprovalService> services;
    public FinancialApprovalsController(ObjectProvider<GlFinancialApprovalService> services){this.services=services;}

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId="requestFinancialApproval",summary="Request approval for the complete retained posting and full mapping proposal",
            description="Retain this approval ID, command ID and exact embedded originalPosting bytes. The proposal creates no authority; an independent checker and current owner state are required.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalRequest.class)))
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalReceipt.class)))
    public Mono<ResponseEntity<GlFinancialApprovalReceipt>> request(@PathVariable String tenantId,@PathVariable String bookId,
            @PathVariable UUID approvalId,ServerHttpRequest request){
        return body(request).flatMap(bytes->Mono.defer(()->{
            var command=decode(bytes,GlFinancialApprovalRequest.class);command(command.commandId());
            if(command.businessUntilEpochMillis()==null||command.mappingSnapshot()==null||command.originalPosting()==null)throw GlPostingRules.bad();
            return result(tenantId,bookId,approvalId,service().requestMapped(tenantId,bookId,approvalId,command.commandId(),
                    command.businessUntilEpochMillis(),embeddedPosting(bytes),command.mappingSnapshot()));
        }));
    }

    @PostMapping(value="/check",consumes=MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId="checkFinancialApproval",summary="Independently check the exact retained proposal at its expected revision")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalCheckRequest.class)))
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalReceipt.class)))
    public Mono<ResponseEntity<GlFinancialApprovalReceipt>> check(@PathVariable String tenantId,@PathVariable String bookId,
            @PathVariable UUID approvalId,ServerHttpRequest request){return transition(tenantId,bookId,approvalId,request,true);}

    @PostMapping(value="/revoke",consumes=MediaType.APPLICATION_JSON_VALUE)
    @DisableIdempotency
    @Operation(operationId="revokeFinancialApproval",summary="Revoke the retained approval at its expected revision")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalTransitionRequest.class)))
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalReceipt.class)))
    public Mono<ResponseEntity<GlFinancialApprovalReceipt>> revoke(@PathVariable String tenantId,@PathVariable String bookId,
            @PathVariable UUID approvalId,ServerHttpRequest request){return transition(tenantId,bookId,approvalId,request,false);}

    @GetMapping
    @DisableIdempotency
    @Operation(operationId="readFinancialApproval",summary="Review the currently authorized complete retained posting and mapping snapshot")
    @ApiResponse(responseCode="200",content=@Content(mediaType="application/json",schema=@Schema(implementation=GlFinancialApprovalDetail.class)))
    public Mono<ResponseEntity<GlFinancialApprovalDetail>> current(@PathVariable String tenantId,@PathVariable String bookId,@PathVariable UUID approvalId){
        return Mono.defer(()->service().detail(tenantId,bookId,approvalId).switchIfEmpty(Mono.error(unobserved())).map(detail->{
            try{
                if(detail.approval()==null||!approvalId.equals(detail.approval().approvalId())||detail.snapshotId()==null||detail.originalPosting()==null
                        ||!Set.of("REQUESTED","APPROVED","REVOKED").contains(detail.approval().state())||detail.approval().revision()<0||detail.approval().revision()>2
                        ||detail.approval().lastCommandId()==null||!GlLedgerMappingSnapshotRules.FORMAT.equals(detail.snapshotFormat()))throw unobserved();
                GlPostingRules.uuid(detail.snapshotId().toString());
                var posting=GlPostingIntentCodec.decode(tenantId,bookId,detail.originalPostingJson()).validated();
                if(!posting.request().equals(detail.originalPosting())||!posting.intentDigest().equals(detail.intentDigest()))throw unobserved();
                var mapping=GlLedgerMappingSnapshotRules.validate(detail.mappingSnapshot());
                if(!mapping.snapshotSha256().equals(detail.snapshotSha256()))throw unobserved();
                GlLedgerMappingSnapshotRules.matches(tenantId,bookId,mapping,posting);
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(detail);
            }catch(Exception failure){throw unobserved();}
        }));
    }

    private Mono<ResponseEntity<GlFinancialApprovalReceipt>> transition(String tenant,String book,UUID approval,ServerHttpRequest request,boolean check){
        return body(request).flatMap(bytes->Mono.defer(()->{
            if(check){
                var command=decode(bytes,GlFinancialApprovalCheckRequest.class);command(command.commandId());revision(command.expectedRevision());
                if(command.expectedSnapshotId()==null)throw GlPostingRules.bad();GlPostingRules.uuid(command.expectedSnapshotId().toString());
                GlPostingRules.digestField(command.expectedSnapshotSha256());GlPostingRules.digestField(command.expectedIntentDigest());
                return result(tenant,book,approval,service().checkMapped(tenant,book,approval,command.commandId(),command.expectedRevision(),
                        command.expectedSnapshotId(),command.expectedSnapshotSha256(),command.expectedIntentDigest()));
            }
            var command=decode(bytes,GlFinancialApprovalTransitionRequest.class);command(command.commandId());revision(command.expectedRevision());
            return result(tenant,book,approval,service().revoke(tenant,book,approval,command.commandId(),command.expectedRevision()));
        }));
    }
    private GlFinancialApprovalService service(){
        var available=services.orderedStream().limit(2).toList();
        if(available.size()!=1)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Unique current owner financial approval service required");
        return available.getFirst();
    }
    private static Mono<ResponseEntity<GlFinancialApprovalReceipt>> result(String tenant,String book,UUID approval,Mono<GlFinancialApprovalControl> result){
        return result.switchIfEmpty(Mono.error(unobserved())).map(row->{
            if(!row.tenantId().toString().equals(tenant)||!row.bookId().toString().equals(book)||!row.approvalId().equals(approval)
                    ||!Set.of("REQUESTED","APPROVED","REVOKED").contains(row.state())||row.revision()<0||row.revision()>2||row.lastEventId()==null)throw unobserved();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                    .body(new GlFinancialApprovalReceipt(row.approvalId(),row.state(),row.revision(),row.approvedUntilEpochMillis(),row.lastEventId()));
        });
    }
    private static <T>T decode(byte[] bytes,Class<T> type){try{return JSON.readValue(bytes,type);}catch(Exception failure){throw GlPostingRules.bad();}}
    private static void command(UUID id){if(id==null)throw GlPostingRules.bad();GlPostingRules.uuid(id.toString());}
    private static void revision(Long revision){if(revision==null||revision<0||revision>2)throw GlPostingRules.bad();}
    private static byte[] embeddedPosting(byte[] bytes){
        try(var parser=JSON.getFactory().createParser(bytes)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw GlPostingRules.bad();
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                String name=parser.currentName();parser.nextToken();
                if(name.equals("originalPosting")){
                    if(parser.currentToken()!=JsonToken.START_OBJECT)throw GlPostingRules.bad();
                    int start=Math.toIntExact(parser.currentTokenLocation().getByteOffset());parser.skipChildren();
                    int end=Math.toIntExact(parser.currentLocation().getByteOffset());return Arrays.copyOfRange(bytes,start,end);
                }
                parser.skipChildren();
            }
        }catch(Exception failure){throw GlPostingRules.bad();}
        throw GlPostingRules.bad();
    }
    private static Mono<byte[]> body(ServerHttpRequest request){
        return DataBufferUtils.join(request.getBody(),MAX_BYTES).switchIfEmpty(Mono.error(GlPostingRules.bad())).map(buffer->{
            try{var bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes;}finally{DataBufferUtils.release(buffer);}
        }).onErrorMap(DataBufferLimitException.class,error->GlPostingRules.bad());
    }
    private static ResponseStatusException unobserved(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Exact approval completion unobserved; retain original command identities");}
}
