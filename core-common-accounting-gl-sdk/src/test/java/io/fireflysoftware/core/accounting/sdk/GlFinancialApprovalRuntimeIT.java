/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.sdk;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.core.accounting.core.services.posting.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.*;
import io.fireflysoftware.core.accounting.core.services.posting.approval.impl.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.*;
import io.fireflysoftware.core.accounting.core.services.posting.ledger.impl.CoreApprovedLedgerMappingPortImpl;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.*;
import io.fireflysoftware.core.accounting.intefaces.dtos.posting.ledger.GlLedgerSourceContracts.*;
import io.fireflysoftware.core.accounting.models.repositories.approval.impl.SpringDataGlFinancialApprovalStoreImpl;
import io.fireflysoftware.core.accounting.invoker.ApiClient;
import io.fireflysoftware.core.accounting.sdk.api.FinancialApprovalsApi;
import io.fireflysoftware.core.accounting.sdk.impl.GlApprovalHttpAuthorityFixtureImpl;
import io.fireflysoftware.core.accounting.sdk.impl.GlApprovalMainProcessFixtureImpl;
import io.fireflysoftware.core.accounting.sdk.model.GlFinancialApprovalRequest;
import io.fireflysoftware.core.accounting.sdk.model.GlFinancialApprovalCheckRequest;
import io.fireflysoftware.core.accounting.sdk.model.GlFinancialApprovalTransitionRequest;
import io.r2dbc.pool.*;
import io.r2dbc.postgresql.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.security.adapter.opa.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

/** Real mapped PostgreSQL/audit roots, with an explicitly controlled staff/PDP transport boundary. */
@Testcontainers
class GlFinancialApprovalRuntimeIT {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("synthetic_gl_approval").withUsername("gl_installer").withPassword(UUID.randomUUID().toString())
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),
                            new com.github.dockerjava.api.model.ExposedPort(5432))));

    private static final String PRINCIPAL = GlFinancialApprovalRuntimeIT.class.getName();
    private static final String ISSUER = "https://synthetic.finance-identity.invalid";
    private static final String APPROVER = "https://synthetic.finance-approval.invalid";
    private static final String PLATFORM = UUID.randomUUID().toString();
    private static final UUID MAKER_STAFF = UUID.randomUUID(), CHECKER_STAFF = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID(), BOOK = UUID.randomUUID(), FAIL_APPROVAL = UUID.randomUUID();
    private static final String WRITER_PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean POLICY_CURRENT = new AtomicBoolean(true);
    private static ConnectionPool pool;
    private static HttpServer policyServer;
    private static WebClient policyClient;
    private static GlFinancialApprovalService service;
    private static DurableAccountingApprovalPortImpl approvalPort;
    private static GlApprovedLedgerMappingPort mappingPort;
    private static final UUID CURRENCY=UUID.randomUUID(), POLICY=UUID.randomUUID(), MASTER_CURRENCY=UUID.randomUUID();
    private static final String POLICY_BODY="MASTERS_CURRENCY_POLICY_V1 synthetic owned policy";

    @BeforeAll
    static void start() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner("REVOKE CREATE ON SCHEMA public FROM PUBLIC;"
                + " CREATE ROLE firefly_audit_guard NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE NOREPLICATION;"
                + " CREATE ROLE firefly_audit_writer LOGIN PASSWORD '" + WRITER_PASSWORD + "' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE NOREPLICATION;"
                + " CREATE ROLE firefly_audit_reader LOGIN PASSWORD '" + UUID.randomUUID() + "' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE NOREPLICATION;");
        for (var resource : List.of("db/optional/immutable-audit/postgresql.sql", "db/optional/immutable-audit/completed-append.sql")) {
            try (var stream = GlFinancialApprovalRuntimeIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertThat(stream).as("Genuine framework-owned audit installer").isNotNull();
                owner(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/optional/financial-approval").table("gl_financial_approval_schema_history")
                .baselineOnMigrate(true).baselineVersion("9").load().migrate();
        seedOwnedBook();
        owner("GRANT USAGE ON SCHEMA public TO firefly_audit_writer;"
                + " GRANT SELECT ON public.gl_account,public.gl_native_book,public.gl_native_currency_admission,public.gl_native_account,"
                + "public.gl_native_period,public.gl_native_mapping,public.gl_native_book_sequence,public.gl_native_journal_action,"
                + "public.gl_native_posting,public.gl_native_entry,public.gl_native_line,public.gl_native_journal_event TO firefly_audit_writer;"
                + " CREATE FUNCTION public.synthetic_approval_late_refusal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " IF NEW.approval_id='" + FAIL_APPROVAL + "'::uuid AND NEW.state='APPROVED' THEN RAISE EXCEPTION 'SYNTHETIC late approval refusal' USING ERRCODE='23514';END IF;RETURN NEW;END $$;"
                + " CREATE TRIGGER synthetic_approval_late_refusal BEFORE UPDATE ON public.gl_financial_approval_control FOR EACH ROW EXECUTE FUNCTION public.synthetic_approval_late_refusal();");
        policyServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        policyServer.createContext("/v1/data/", exchange -> {
            try (exchange) {
                var request = JSON.readTree(exchange.getRequestBody().readNBytes(64 * 1024));
                var input = request.path("input");
                var actor = input.path("subject");
                var context = input.path("context");
                String action = input.path("action").asText(), resource = input.path("resource").asText();
                boolean allowed = POLICY_CURRENT.get() && ISSUER.equals(actor.path("issuer").asText())
                        && PLATFORM.equals(actor.path("tenantId").asText())
                        && Set.of("maker", "checker").contains(actor.path("subject").asText())
                        && TENANT.toString().equals(context.path("tenantId").asText())
                        && BOOK.toString().equals(context.path("bookId").asText())
                        && (resource.equals("accounting/book/" + BOOK) || resource.startsWith("accounting/account/"))
                        && (action.equals("accounting.gl.read") || action.startsWith("accounting.gl.approval."));
                byte[] response = (exchange.getRequestURI().getPath().endsWith("/current")
                        ? JSON.writeValueAsBytes(Map.of("result", Map.of("allow", allowed, "policy_revision", "synthetic-policy-r1"), "decision_id", UUID.randomUUID().toString()))
                        : JSON.writeValueAsBytes(Map.of("result", allowed)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);exchange.getResponseBody().write(response);
            }
        });
        policyServer.start();
        policyClient = WebClient.builder().baseUrl("http://127.0.0.1:" + policyServer.getAddress().getPort()).build();
        compose();
    }

    private static void compose() {
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(new PostgresqlConnectionFactory(
                PostgresqlConnectionConfiguration.builder().host(postgres.getHost()).port(postgres.getMappedPort(5432))
                        .database(postgres.getDatabaseName()).username("firefly_audit_writer").password(WRITER_PASSWORD).build()))
                .initialSize(0).maxSize(1).maxAcquireTime(Duration.ofSeconds(5)).build());
        var factory = new OwnedLeaseConnectionFactory(pool);
        var client = DatabaseClient.create(factory);
        var template = new R2dbcEntityTemplate(client, PostgresDialect.INSTANCE);
        var manager = new R2dbcTransactionManager(factory);
        Clock clock = Clock.systemUTC();
        java.util.function.LongSupplier nanos = System::nanoTime;
        SecurityContextPort security = () -> Mono.deferContextual(context -> Mono.justOrEmpty(context.<SecurityPrincipal>getOrEmpty(PRINCIPAL)));
        var decisions = new OpaPolicyDecisionAdapter(policyClient, "synthetic/read");
        var policies = new OpaPolicyEvaluationAdapter(policyClient, "synthetic/current");
        var beans = new StaticListableBeanFactory();beans.addBean("security", security);beans.addBean("policy", decisions);
        var environment = new MockEnvironment().withProperty("firefly.security.resource-server.strict-policy", "true")
                .withProperty("firefly.accounting.approval.trusted-issuers", APPROVER);
        var authority = new GlAccountingAuthority(beans.getBeanProvider(SecurityContextPort.class), beans.getBeanProvider(PolicyDecisionPort.class),
                beans.getBeanProvider(AccountingApprovalPort.class), beans.getBeanProvider(AccountingBookOwnerPort.class), environment);
        var transactions = new GlAccountingTransaction(manager, new GlAccountingDatabaseContext(client), template);
        var store = new SpringDataGlFinancialApprovalStoreImpl(template);
        var lookup = new GlFinancialApprovalLookupImpl(authority, transactions, store, clock);
        var current = new CurrentGlFinancialApprovalAuthorityImpl(security, policies,
                new CurrentGlFinancialApprovalAuthorityImpl.Profile(ISSUER, APPROVER, "azp", "synthetic-staff", Duration.ofMinutes(15)), clock, nanos);
        var resources = BoundedR2dbcResources.verify(factory, client, template, manager);
        var root = new GlFinancialApprovalAuditRootImpl(resources, client, template, manager,
                new CleanupPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2)), current, clock, nanos);
        root.requireTemplate(template);store.requireTemplate(template);
        service = new DurableGlFinancialApprovalServiceImpl(current, root, lookup, store, clock, nanos);
        approvalPort = new DurableAccountingApprovalPortImpl(lookup, current, APPROVER, clock, nanos);
        mappingPort = new CoreApprovedLedgerMappingPortImpl(lookup,current,clock,nanos);
    }

    @AfterAll
    static void stop() {
        if (policyServer != null) policyServer.stop(0);
        if (pool != null) pool.disposeLater().block(Duration.ofSeconds(5));
    }

    private static SecurityPrincipal actor(String subject, UUID staff) {
        return SecurityPrincipal.builder().issuer(ISSUER).subject(subject).tenantId(PLATFORM)
                .claims(Map.of("exp", Instant.now().plusSeconds(60), "principal_kind", "STAFF", "staff_actor_id", staff.toString(), "azp", "synthetic-finance")).build();
    }

    private static <T> T as(SecurityPrincipal principal, Mono<T> operation) {
        return operation.contextWrite(context -> context.put(PRINCIPAL, principal)).block(Duration.ofSeconds(10));
    }

    private static byte[] posting(UUID action) throws Exception {
        var currency = new GlCurrencyReference(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "USD", 1L, "a".repeat(64), "MASTERS_CURRENCY_POLICY_V1");
        var admission = UUID.randomUUID().toString();
        return JSON.writeValueAsBytes(new GlPostingRequest(action.toString(), UUID.randomUUID().toString(), 0L, 0L,
                Instant.now().plusSeconds(3600).toEpochMilli(), "2026-11-01", Instant.now().toEpochMilli(),
                new GlSourceReference("MANUAL_ADJUSTMENT", "synthetic-approval-only", "synthetic-effect", 1L, "b".repeat(64),
                        "SYNTHETIC_ADJUSTMENT", 0, "synthetic-receipt", "synthetic-provenance", Instant.now().toEpochMilli()),
                UUID.randomUUID().toString(), 1L, "c".repeat(64), "Synthetic approval, no posting", List.of(new GlPostingEntry("001", "Synthetic",
                List.of(new GlPostingLine("001", UUID.randomUUID().toString(), 0L, admission, 0L, currency, 2, "DR", "101", "Synthetic"),
                        new GlPostingLine("002", UUID.randomUUID().toString(), 0L, admission, 0L, currency, 2, "CR", "101", "Synthetic"))))));
    }

    private static void refused(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void durableMakerCheckerApprovalRetainsExactIntentAcrossNewApplicationComposition() throws Exception {
        var maker = actor("maker", MAKER_STAFF);var checker = actor("checker", CHECKER_STAFF);
        var approval = UUID.randomUUID();var command = UUID.randomUUID();var action = UUID.randomUUID();var original = posting(action);
        long until = Instant.now().plusSeconds(600).toEpochMilli();
        var requested = as(maker, service.request(TENANT.toString(), BOOK.toString(), approval, command, until, original));
        assertThat(requested.state()).isEqualTo("REQUESTED");
        assertThat(as(maker, service.request(TENANT.toString(), BOOK.toString(), approval, command, until, original))).isEqualTo(requested);
        refused(() -> as(actor("maker", UUID.randomUUID()), service.check(TENANT.toString(), BOOK.toString(), approval, UUID.randomUUID(), 0)));
        refused(() -> as(actor("checker", MAKER_STAFF), service.check(TENANT.toString(), BOOK.toString(), approval, UUID.randomUUID(), 0)));
        var checked = as(checker, service.check(TENANT.toString(), BOOK.toString(), approval, UUID.randomUUID(), 0));
        assertThat(checked.state()).isEqualTo("APPROVED");assertThat(checked.revision()).isEqualTo(1);
        assertThat(checked.approvedUntilEpochMillis()).isEqualTo(until);
        assertThat(checked.approvedUntilEpochMillis()).isGreaterThan(Instant.now().plusSeconds(5).toEpochMilli());
        var decoded = GlPostingIntentCodec.decode(TENANT.toString(), BOOK.toString(), original);
        var intent = new AccountingApprovalIntent(TENANT.toString(), BOOK.toString(), "post", decoded.validated().intentDigest(),
                decoded.validated().canonicalIntent(), Map.of("actionId", action.toString()));
        var admitted = as(maker, approvalPort.approve(maker, intent));
        assertThat(admitted.validUntilEpochMillis()).isLessThan(until)
                .isLessThanOrEqualTo(Instant.now().plusSeconds(5).toEpochMilli());
        var tokenExpiry = Instant.now().plusSeconds(3);
        var shortClaims = new HashMap<String, Object>(maker.claims());shortClaims.put("exp", tokenExpiry);
        var shortTokenMaker = maker.toBuilder().claims(shortClaims).build();
        assertThat(as(shortTokenMaker, approvalPort.approve(shortTokenMaker, intent)).validUntilEpochMillis())
                .isLessThanOrEqualTo(tokenExpiry.toEpochMilli());
        assertThat(as(maker, service.current(TENANT.toString(), BOOK.toString(), approval)).approvedUntilEpochMillis())
                .isEqualTo(until);
        pool.disposeLater().block(Duration.ofSeconds(5));compose();
        assertThat(as(maker, service.current(TENANT.toString(), BOOK.toString(), approval))).isEqualTo(checked);
        assertThat(as(maker, approvalPort.approve(maker, intent)).approvalReference()).isEqualTo(approval.toString());
        assertThat(count("firefly_audit.audit_event", "resource_id", approval)).isEqualTo(2);
        assertThat(count("firefly_audit.audit_outbox", "resource_id", approval)).isEqualTo(2);
        assertThat(count("public.gl_financial_approval_transition", "approval_id", approval)).isEqualTo(2);
        POLICY_CURRENT.set(false);
        try { refused(() -> as(maker, service.current(TENANT.toString(), BOOK.toString(), approval))); }
        finally { POLICY_CURRENT.set(true); }
        assertThat(count("public.gl_native_posting", "action_id", action)).isZero();
    }

    @Test
    void revokedApprovalCannotBeConsumedAndReplayedCommandCannotRestoreAuthority() throws Exception {
        var maker = actor("maker", MAKER_STAFF);var checker = actor("checker", CHECKER_STAFF);
        var approval = UUID.randomUUID();var action = UUID.randomUUID();var original = posting(action);var checkCommand = UUID.randomUUID();
        as(maker, service.request(TENANT.toString(), BOOK.toString(), approval, UUID.randomUUID(), Instant.now().plusSeconds(600).toEpochMilli(), original));
        as(checker, service.check(TENANT.toString(), BOOK.toString(), approval, checkCommand, 0));
        var revoked = as(maker, service.revoke(TENANT.toString(), BOOK.toString(), approval, UUID.randomUUID(), 1));
        assertThat(revoked.state()).isEqualTo("REVOKED");assertThat(revoked.revision()).isEqualTo(2);
        assertThat(as(checker, service.check(TENANT.toString(), BOOK.toString(), approval, checkCommand, 0))).isEqualTo(revoked);
        var decoded = GlPostingIntentCodec.decode(TENANT.toString(), BOOK.toString(), original);
        var intent = new AccountingApprovalIntent(TENANT.toString(), BOOK.toString(), "post", decoded.validated().intentDigest(), decoded.validated().canonicalIntent(), Map.of("actionId", action.toString()));
        refused(() -> as(maker, approvalPort.approve(maker, intent)));
        assertThat(count("firefly_audit.audit_event", "resource_id", approval)).isEqualTo(3);
    }

    @Test
    void lateMappedLifecycleRefusalRollsBackTheAuditAndTransitionOnTheSameRoot() throws Exception {
        var maker = actor("maker", MAKER_STAFF);var checker = actor("checker", CHECKER_STAFF);
        as(maker, service.request(TENANT.toString(), BOOK.toString(), FAIL_APPROVAL, UUID.randomUUID(), Instant.now().plusSeconds(600).toEpochMilli(), posting(UUID.randomUUID())));
        assertThatThrownBy(() -> as(checker, service.check(TENANT.toString(), BOOK.toString(), FAIL_APPROVAL, UUID.randomUUID(), 0))).isInstanceOf(RuntimeException.class);
        var retained = as(maker, service.current(TENANT.toString(), BOOK.toString(), FAIL_APPROVAL));
        assertThat(retained.state()).isEqualTo("REQUESTED");assertThat(retained.revision()).isZero();
        assertThat(count("firefly_audit.audit_event", "resource_id", FAIL_APPROVAL)).isEqualTo(1);
        assertThat(count("firefly_audit.audit_outbox", "resource_id", FAIL_APPROVAL)).isEqualTo(1);
        assertThat(count("public.gl_financial_approval_transition", "approval_id", FAIL_APPROVAL)).isEqualTo(1);
    }

    @Test
    void runtimeCannotReadWithoutCurrentScopeMutateOriginalsOrTruncateApprovalHistory() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "firefly_audit_writer", WRITER_PASSWORD); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM public.gl_financial_approval_definition")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();
            }
            assertThatThrownBy(() -> statement.execute("TRUNCATE public.gl_financial_approval_transition"))
                    .isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            assertThat(statement.executeUpdate("UPDATE public.gl_financial_approval_control SET state='APPROVED'" )).isZero();
            assertThatThrownBy(() -> statement.execute("DELETE FROM public.gl_financial_approval_definition"))
                    .isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    private static void owner(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void seedOwnedBook() throws Exception {
        owner("BEGIN;INSERT INTO public.gl_native_book(id,tenant_id,legal_entity_reference,book_code,definition_digest,canonical_definition,tenant_observation_digest,timezone,calendar_reference,calendar_version,calendar_digest,functional_currency_admission_id,enrollment_action_id) VALUES('"
                +BOOK+"','"+TENANT+"','synthetic-bank','synthetic-map-book','"+GlPostingRules.digest("synthetic book")+"','synthetic book','"+"a".repeat(64)+"','UTC','synthetic-calendar',1,'"+"b".repeat(64)+"','"+CURRENCY+"','"+UUID.randomUUID()+"');"
                +" INSERT INTO public.gl_native_currency_admission(id,tenant_id,book_id,policy_id,currency_id,iso_code,policy_version,policy_digest,policy_encoding,canonical_policy,posted_scale,definition_digest,canonical_definition,enrollment_action_id) VALUES('"
                +CURRENCY+"','"+TENANT+"','"+BOOK+"','"+POLICY+"','"+MASTER_CURRENCY+"','USD',1,'"+GlPostingRules.digest(POLICY_BODY)+"','MASTERS_CURRENCY_POLICY_V1','"+POLICY_BODY+"',2,'"+GlPostingRules.digest("synthetic admission")+"','synthetic admission','"+UUID.randomUUID()+"');COMMIT;");
    }

    private static MappingSnapshot mappingProposal() throws Exception {
        var control=UUID.randomUUID();var source=UUID.randomUUID();var destination=UUID.randomUUID();var mapping=UUID.randomUUID();
        var sql=new StringBuilder("BEGIN;");
        for(var account:List.of(control,source,destination))sql.append("INSERT INTO public.gl_account(id,code,name,type,parent_id,is_leaf,currency) VALUES('")
            .append(account).append("','SYN-").append(account).append("','Synthetic mapping chart','LIABILITY',")
            .append(account.equals(control)?"NULL":"'"+control+"'").append(",").append(!account.equals(control)).append(",'USD');");
        for(var account:List.of(control,source,destination)){
            var canonical="synthetic account "+account;
            sql.append("INSERT INTO public.gl_native_account(account_id,tenant_id,book_id,kind,parent_account_id,currency_admission_id,source_code,source_type,source_currency,definition_digest,canonical_definition,enrollment_action_id) VALUES('")
                .append(account).append("','").append(TENANT).append("','").append(BOOK).append("','").append(account.equals(control)?"CONTROL":"POSTING").append("',")
                .append(account.equals(control)?"NULL":"'"+control+"'").append(",").append(account.equals(control)?"NULL":"'"+CURRENCY+"'")
                .append(",'SYN-").append(account).append("','LIABILITY','USD','").append(GlPostingRules.digest(canonical)).append("','").append(canonical).append("','").append(UUID.randomUUID()).append("');");
        }
        var definition="synthetic retained original mapping "+mapping;var digest=GlPostingRules.digest(definition);
        sql.append("INSERT INTO public.gl_native_mapping(id,tenant_id,book_id,mapping_reference,revision,definition_digest,canonical_definition,enrollment_action_id) VALUES('")
            .append(mapping).append("','").append(TENANT).append("','").append(BOOK).append("','SYN-").append(mapping).append("',1,'").append(digest).append("','").append(definition).append("','").append(UUID.randomUUID()).append("');COMMIT;");
        owner(sql.toString());
        var masters=new GlCurrencyReference(POLICY.toString(),MASTER_CURRENCY.toString(),"USD",1L,GlPostingRules.digest(POLICY_BODY),"MASTERS_CURRENCY_POLICY_V1");
        return new MappingSnapshot(TENANT.toString(),BOOK,new MappingReference(mapping,1,digest,MappingPurpose.INTERNAL_CUSTOMER_TRANSFER),
                new AccountBinding(new LedgerAccountReference(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),new PostingLeaf(TENANT.toString(),BOOK,source,1,"POSTING","LIABILITY",List.of(new ControlReference(control,1)))),
                new AccountBinding(new LedgerAccountReference(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),new PostingLeaf(TENANT.toString(),BOOK,destination,1,"POSTING","LIABILITY",List.of(new ControlReference(control,1)))),
                new CurrencyCrosswalk(UUID.randomUUID(),1,GlPostingRules.digest("synthetic crosswalk reference"),"synthetic-ledger-usd",2,masters,CURRENCY,1));
    }

    private static byte[] mappedPosting(UUID action,MappingSnapshot mapping) throws Exception {
        var c=mapping.currency();var lines=new ArrayList<GlPostingLine>();int ordinal=0;
        for(var binding:List.of(mapping.source(),mapping.destination())){
            ordinal++;lines.add(new GlPostingLine(ordinal==1?"001":"002",binding.leaf().accountId().toString(),1L,CURRENCY.toString(),1L,c.masters(),2,
                    ordinal==1?"DR":"CR","101","Internal customer transfer"));
        }
        return JSON.writeValueAsBytes(new GlPostingRequest(action.toString(),UUID.randomUUID().toString(),1L,1L,Instant.now().plusSeconds(3600).toEpochMilli(),
                "2026-11-01",Instant.now().toEpochMilli(),new GlSourceReference("LEDGER",UUID.randomUUID().toString(),UUID.randomUUID().toString(),1L,"b".repeat(64),"COMMIT",0,
                        UUID.randomUUID().toString(),UUID.randomUUID().toString(),Instant.now().toEpochMilli()),mapping.reference().id().toString(),1L,mapping.reference().digest(),
                "Synthetic approval proposal, no Ledger or journal effects",List.of(new GlPostingEntry("001","Synthetic proposal",lines))));
    }

    private static io.fireflysoftware.core.accounting.models.entities.approval.GlFinancialApprovalControl reviewedCheck(SecurityPrincipal checker,UUID approval,UUID command){
        var review=as(checker,service.detail(TENANT.toString(),BOOK.toString(),approval));
        return as(checker,service.checkMapped(TENANT.toString(),BOOK.toString(),approval,command,review.approval().revision(),review.snapshotId(),review.snapshotSha256(),review.intentDigest()));
    }

    private static GlApprovedLedgerMappingPort.Approved approvedMapping(SecurityPrincipal maker,byte[] original) {
        var v=GlPostingIntentCodec.decode(TENANT.toString(),BOOK.toString(),original).validated();
        var intent=new AccountingApprovalIntent(TENANT.toString(),BOOK.toString(),"post",v.intentDigest(),v.canonicalIntent(),Map.of("actionId",v.request().actionId()));
        var approval=as(maker,approvalPort.approve(maker,intent));
        var admission=new GlAccountingAuthority.Admission(maker,approval,approval.validUntilEpochMillis(),intent,
                Set.of(v.request().entries().getFirst().lines().getFirst().accountId(),v.request().entries().getFirst().lines().getLast().accountId()));
        return as(maker,mappingPort.current(admission,v));
    }

    @Test
    void mappedApprovalPersistsTheFullSnapshotAndIndependentOwnerStateAcrossRecomposition() throws Exception {
        var maker=actor("maker",MAKER_STAFF);var checker=actor("checker",CHECKER_STAFF);var mapping=mappingProposal();
        var approval=UUID.randomUUID();var action=UUID.randomUUID();var original=mappedPosting(action,mapping);var command=UUID.randomUUID();var until=Instant.now().plusSeconds(600).toEpochMilli();
        as(maker,service.requestMapped(TENANT.toString(),BOOK.toString(),approval,command,until,original,mapping));
        refused(()->approvedMapping(maker,original));
        refused(()->reviewedCheck(actor("checker",MAKER_STAFF),approval,UUID.randomUUID()));
        reviewedCheck(checker,approval,UUID.randomUUID());
        var retained=approvedMapping(maker,original);var expected=GlLedgerMappingSnapshotRules.validate(mapping);
        assertThat(retained.snapshot().canonical()).isEqualTo(expected.canonical());assertThat(retained.snapshot().snapshotSha256()).isEqualTo(expected.snapshotSha256());
        assertThat(retained.snapshot().snapshotSha256()).isNotEqualTo(mapping.reference().digest());
        pool.disposeLater().block(Duration.ofSeconds(5));compose();
        assertThat(approvedMapping(maker,original).snapshotId()).isEqualTo(retained.snapshotId());
        assertThat(count("public.gl_financial_approval_mapping","approval_id",approval)).isEqualTo(1);
        assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(2);
        assertThat(count("public.gl_native_posting","action_id",action)).isZero();
    }

    @Test
    void mappedReplayCannotReplaceTheCrosswalkAndRevocationClosesOwnerObservation() throws Exception {
        var maker=actor("maker",MAKER_STAFF);var checker=actor("checker",CHECKER_STAFF);var mapping=mappingProposal();
        var approval=UUID.randomUUID();var action=UUID.randomUUID();var command=UUID.randomUUID();var original=mappedPosting(action,mapping);var until=Instant.now().plusSeconds(600).toEpochMilli();
        as(maker,service.requestMapped(TENANT.toString(),BOOK.toString(),approval,command,until,original,mapping));
        var c=mapping.currency();var changed=new MappingSnapshot(mapping.tenant(),mapping.bookId(),mapping.reference(),mapping.source(),mapping.destination(),
                new CurrencyCrosswalk(c.id(),c.version(),c.digest(),"changed-ledger-policy",c.scale(),c.masters(),c.admissionId(),c.admissionVersion()));
        refused(()->as(maker,service.requestMapped(TENANT.toString(),BOOK.toString(),approval,command,until,original,changed)));
        reviewedCheck(checker,approval,UUID.randomUUID());assertThat(approvedMapping(maker,original)).isNotNull();
        as(maker,service.revoke(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),1));refused(()->approvedMapping(maker,original));
        assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(3);
        assertThat(count("public.gl_native_posting","action_id",action)).isZero();
    }

    @Test
    void nativeSnapshotCanonicalMatchesEveryJavaFieldAndStaleAncestorRefusesCurrentMapping() throws Exception {
        var maker=actor("maker",MAKER_STAFF);var checker=actor("checker",CHECKER_STAFF);var mapping=mappingProposal();
        var validated=GlLedgerMappingSnapshotRules.validate(mapping);
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
                var query=connection.prepareStatement("SELECT public.gl_ledger_mapping_snapshot_canonical_v1(?)")) {
            query.setString(1,validated.originalJson());try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(validated.canonical());}
        }
        var approval=UUID.randomUUID();var original=mappedPosting(UUID.randomUUID(),mapping);
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
                var query=connection.prepareStatement("SELECT public.gl_ledger_mapping_posting_matches_v1(?,?)")) {
            query.setString(1,validated.originalJson());query.setString(2,new String(original,StandardCharsets.UTF_8));
            try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isTrue();}
            var changed=JSON.readTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("entries").get(0).path("lines").get(0))
                    .put("accountId",mapping.destination().leaf().accountId().toString());
            query.setString(2,JSON.writeValueAsString(changed));
            try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isFalse();}
        }
        as(maker,service.requestMapped(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),Instant.now().plusSeconds(600).toEpochMilli(),original,mapping));
        reviewedCheck(checker,approval,UUID.randomUUID());assertThat(approvedMapping(maker,original)).isNotNull();
        owner("UPDATE public.gl_native_account SET lifecycle='SUSPENDED',control_version=control_version+1 WHERE account_id='"+mapping.source().leaf().controlAncestors().getFirst().accountId()+"'");
        assertThatThrownBy(()->approvedMapping(maker,original)).isInstanceOf(RuntimeException.class);
        assertThat(as(maker,service.revoke(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),1)).state()).isEqualTo("REVOKED");
    }

    @Test
    void checkerCanReviewTheExactOwnerProposalAndStaleOrTamperedReviewCannotApproveOrReplay() throws Exception {
        var maker=actor("maker",MAKER_STAFF);var checker=actor("checker",CHECKER_STAFF);var mapping=mappingProposal();
        var approval=UUID.randomUUID();var action=UUID.randomUUID();var original=mappedPosting(action,mapping);
        as(maker,service.requestMapped(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),Instant.now().plusSeconds(600).toEpochMilli(),original,mapping));
        refused(()->as(checker,service.check(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),0)));
        assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(1);
        assertThat(count("firefly_audit.audit_outbox","resource_id",approval)).isEqualTo(1);
        var review=as(checker,service.detail(TENANT.toString(),BOOK.toString(),approval));
        assertThat(review.approval().state()).isEqualTo("REQUESTED");assertThat(review.approval().revision()).isZero();
        assertThat(review.originalPostingJson()).isEqualTo(new String(original,StandardCharsets.UTF_8));assertThat(review.mappingSnapshot()).isEqualTo(mapping);
        assertThat(review.originalPosting()).isEqualTo(GlPostingIntentCodec.decode(TENANT.toString(),BOOK.toString(),original).validated().request());
        assertThat(review.snapshotSha256()).isEqualTo(GlLedgerMappingSnapshotRules.validate(mapping).snapshotSha256());
        var badSnapshot=(review.snapshotSha256().startsWith("a")?"b":"a")+review.snapshotSha256().substring(1);
        var badIntent=(review.intentDigest().startsWith("a")?"b":"a")+review.intentDigest().substring(1);
        refused(()->as(checker,service.checkMapped(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),0,UUID.randomUUID(),review.snapshotSha256(),review.intentDigest())));
        refused(()->as(checker,service.checkMapped(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),0,review.snapshotId(),badSnapshot,review.intentDigest())));
        refused(()->as(checker,service.checkMapped(TENANT.toString(),BOOK.toString(),approval,UUID.randomUUID(),0,review.snapshotId(),review.snapshotSha256(),badIntent)));
        assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(1);
        assertThat(as(maker,service.current(TENANT.toString(),BOOK.toString(),approval)).state()).isEqualTo("REQUESTED");
        var command=UUID.randomUUID();
        var approved=as(checker,service.checkMapped(TENANT.toString(),BOOK.toString(),approval,command,0,review.snapshotId(),review.snapshotSha256(),review.intentDigest()));
        assertThat(approved.state()).isEqualTo("APPROVED");
        refused(()->as(checker,service.check(TENANT.toString(),BOOK.toString(),approval,command,0)));
        assertThat(as(checker,service.current(TENANT.toString(),BOOK.toString(),approval))).isEqualTo(approved);
        assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(2);
        assertThat(count("firefly_audit.audit_outbox","resource_id",approval)).isEqualTo(2);
        refused(()->as(checker,service.checkMapped(TENANT.toString(),BOOK.toString(),approval,command,0,review.snapshotId(),badSnapshot,review.intentDigest())));
        assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(2);
        assertThat(count("public.gl_native_posting","action_id",action)).isZero();
    }

    private static ApiClient generatedClient(String base,String token){
        var client=new ApiClient();client.setBasePath(base);if(token!=null)client.addDefaultHeader("Authorization","Bearer "+token);return client;
    }
    private static void sdkRefused(Map<String,Object> probes,String name,int expected,Runnable invocation){
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(WebClientResponseException.class,error->{
            assertThat(error.getStatusCode().value()).isEqualTo(expected);var body=error.getResponseBodyAsByteArray();assertThat(body.length).isLessThanOrEqualTo(64*1024);
            try{probes.put(name,Map.of("status",expected,"bodyBytes",body.length,"bodySha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body))));}
            catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}
        });
    }
    private static GlFinancialApprovalCheckRequest reviewedCheck(ApiClient client,UUID command,long revision,UUID snapshot,String sha,String intent)throws Exception {
        return client.getObjectMapper().readValue(JSON.writeValueAsBytes(Map.of("commandId",command,"expectedRevision",revision,
                "expectedSnapshotId",snapshot,"expectedSnapshotSha256",sha,"expectedIntentDigest",intent)),GlFinancialApprovalCheckRequest.class);
    }

    @Test
    void signedOriginalMainAndGeneratedSdkRetainReviewedApprovalAcrossRestartAndRevocation() throws Exception {
        var mapping=mappingProposal();var approval=UUID.randomUUID();var action=UUID.randomUUID();var command=UUID.randomUUID();var checkCommand=UUID.randomUUID();
        var original=mappedPosting(action,mapping);long until=Instant.now().plusSeconds(600).toEpochMilli();
        var proof=new LinkedHashMap<String,Object>();var probes=new TreeMap<String,Object>();var states=new ArrayList<String>();
        proof.put("schema","GL_APPROVAL_HTTP_LIFECYCLE_V1");proof.put("status","STARTED");proof.put("sdkClass",FinancialApprovalsApi.class.getName());
        proof.put("negativeResponses",probes);proof.put("observedStates",states);proof.put("financialPostingAccepted",false);proof.put("genuineLedgerSourceAccepted",false);
        var authority=new GlApprovalHttpAuthorityFixtureImpl(TENANT,BOOK,UUID.fromString(PLATFORM));GlApprovalMainProcessFixtureImpl main=null;
        try(authority){
            main=new GlApprovalMainProcessFixtureImpl(postgres.getHost(),postgres.getMappedPort(5432),postgres.getDatabaseName(),postgres.getUsername(),postgres.getPassword(),WRITER_PASSWORD,authority,APPROVER);
            try(var running=main){
                var makerToken=authority.token("maker",MAKER_STAFF);running.ready(makerToken);
                var makerClient=generatedClient(running.baseUrl(),makerToken);var maker=new FinancialApprovalsApi(makerClient);
                var checkerClient=generatedClient(running.baseUrl(),authority.token("checker",CHECKER_STAFF));var checker=new FinancialApprovalsApi(checkerClient);
                var request=makerClient.getObjectMapper().readValue(JSON.writeValueAsBytes(Map.of("commandId",command,"businessUntilEpochMillis",until,
                        "originalPosting",JSON.readTree(original),"mappingSnapshot",mapping)),GlFinancialApprovalRequest.class);
                var requested=maker.requestFinancialApproval(TENANT.toString(),BOOK.toString(),approval,request).block(Duration.ofSeconds(10));
                assertThat(requested).isNotNull();assertThat(requested.getState().toString()).isEqualTo("REQUESTED");assertThat(requested.getRevision()).isZero();
                assertThat(requested.getLastCommandId()).isEqualTo(command);states.add("REQUESTED");
                assertThat(maker.requestFinancialApproval(TENANT.toString(),BOOK.toString(),approval,request).block(Duration.ofSeconds(10))).isEqualTo(requested);
                assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(1);
                var review=checker.readFinancialApproval(TENANT.toString(),BOOK.toString(),approval).block(Duration.ofSeconds(10));
                assertThat(review).isNotNull();assertThat(review.getApproval().getState().toString()).isEqualTo("REQUESTED");
                var exactSdkPosting=makerClient.getObjectMapper().writeValueAsString(request.getOriginalPosting());
                assertThat(review.getOriginalPostingJson()).isEqualTo(exactSdkPosting);
                assertThat(review.getIntentDigest()).isEqualTo(GlPostingIntentCodec.decode(TENANT.toString(),BOOK.toString(),exactSdkPosting).validated().intentDigest());
                assertThat(review.getSnapshotSha256()).isEqualTo(GlLedgerMappingSnapshotRules.validate(mapping).snapshotSha256());
                assertThat(GlLedgerMappingSnapshotRules.decode(checkerClient.getObjectMapper().writeValueAsString(review.getMappingSnapshot())).snapshot()).isEqualTo(mapping);
                states.add("COMPLETE_OWNER_PROPOSAL_REVIEWED");
                var tokens=new LinkedHashMap<String,String>();tokens.put("missing",null);tokens.put("badSignature",authority.invalidSignature("checker",CHECKER_STAFF));
                tokens.put("wrongIssuer",authority.token("checker",CHECKER_STAFF,"https://untrusted.synthetic.invalid",GlApprovalHttpAuthorityFixtureImpl.AUDIENCE,PLATFORM,GlApprovalHttpAuthorityFixtureImpl.SCOPE));
                tokens.put("wrongAudience",authority.token("checker",CHECKER_STAFF,authority.issuer(),"other-owner",PLATFORM,GlApprovalHttpAuthorityFixtureImpl.SCOPE));
                tokens.put("wrongPlatformTenant",authority.token("checker",CHECKER_STAFF,authority.issuer(),GlApprovalHttpAuthorityFixtureImpl.AUDIENCE,UUID.randomUUID().toString(),GlApprovalHttpAuthorityFixtureImpl.SCOPE));
                tokens.put("wrongScope",authority.token("checker",CHECKER_STAFF,authority.issuer(),GlApprovalHttpAuthorityFixtureImpl.AUDIENCE,PLATFORM,"gl:unrelated"));
                for(var token:tokens.entrySet()){
                    var denied=new FinancialApprovalsApi(generatedClient(running.baseUrl(),token.getValue()));int status=token.getKey().startsWith("wrongPlatform")||token.getKey().equals("wrongScope")?403:401;
                    sdkRefused(probes,token.getKey(),status,()->denied.readFinancialApproval(TENANT.toString(),BOOK.toString(),approval).block(Duration.ofSeconds(10)));
                }
                var valid=reviewedCheck(checkerClient,checkCommand,0,review.getSnapshotId(),review.getSnapshotSha256(),review.getIntentDigest());
                var sameSubject=new FinancialApprovalsApi(generatedClient(running.baseUrl(),authority.token("maker",UUID.randomUUID())));
                sdkRefused(probes,"sameIssuerAndSubject",403,()->sameSubject.checkFinancialApproval(TENANT.toString(),BOOK.toString(),approval,valid).block(Duration.ofSeconds(10)));
                var sameStaff=new FinancialApprovalsApi(generatedClient(running.baseUrl(),authority.token("checker",MAKER_STAFF)));
                sdkRefused(probes,"sameCanonicalStaff",403,()->sameStaff.checkFinancialApproval(TENANT.toString(),BOOK.toString(),approval,valid).block(Duration.ofSeconds(10)));
                var wrongSha=(review.getSnapshotSha256().startsWith("a")?"b":"a")+review.getSnapshotSha256().substring(1);
                var changed=reviewedCheck(checkerClient,checkCommand,0,review.getSnapshotId(),wrongSha,review.getIntentDigest());
                sdkRefused(probes,"tamperedReview",403,()->checker.checkFinancialApproval(TENANT.toString(),BOOK.toString(),approval,changed).block(Duration.ofSeconds(10)));
                assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(1);
                var checked=checker.checkFinancialApproval(TENANT.toString(),BOOK.toString(),approval,valid).block(Duration.ofSeconds(10));
                assertThat(checked).isNotNull();assertThat(checked.getState().toString()).isEqualTo("APPROVED");assertThat(checked.getRevision()).isEqualTo(1L);
                assertThat(checked.getApprovedUntilEpochMillis()).isEqualTo(until);states.add("APPROVED");
                running.restart(authority.token("maker",MAKER_STAFF));
                var restartedMakerClient=generatedClient(running.baseUrl(),authority.token("maker",MAKER_STAFF));var restartedMaker=new FinancialApprovalsApi(restartedMakerClient);
                var restartedChecker=new FinancialApprovalsApi(generatedClient(running.baseUrl(),authority.token("checker",CHECKER_STAFF)));
                var retained=restartedChecker.readFinancialApproval(TENANT.toString(),BOOK.toString(),approval).block(Duration.ofSeconds(10));
                assertThat(retained).isNotNull();assertThat(retained.getApproval().getState().toString()).isEqualTo("APPROVED");
                assertThat(retained.getSnapshotId()).isEqualTo(review.getSnapshotId());assertThat(retained.getSnapshotSha256()).isEqualTo(review.getSnapshotSha256());
                assertThat(retained.getOriginalPostingJson()).isEqualTo(exactSdkPosting);assertThat(retained.getBusinessUntilEpochMillis()).isEqualTo(until);states.add("APPROVED_AFTER_MAIN_RESTART");
                authority.current(false);
                try{sdkRefused(probes,"revokedCurrentPolicy",403,()->restartedChecker.readFinancialApproval(TENANT.toString(),BOOK.toString(),approval).block(Duration.ofSeconds(10)));}
                finally{authority.current(true);}
                var revokeCommand=UUID.randomUUID();var revoke=restartedMakerClient.getObjectMapper().readValue(JSON.writeValueAsBytes(Map.of("commandId",revokeCommand,"expectedRevision",1L)),GlFinancialApprovalTransitionRequest.class);
                var revoked=restartedMaker.revokeFinancialApproval(TENANT.toString(),BOOK.toString(),approval,revoke).block(Duration.ofSeconds(10));
                assertThat(revoked).isNotNull();assertThat(revoked.getState().toString()).isEqualTo("REVOKED");assertThat(revoked.getRevision()).isEqualTo(2L);states.add("REVOKED");
                assertThat(restartedChecker.readFinancialApproval(TENANT.toString(),BOOK.toString(),approval).block(Duration.ofSeconds(10)).getApproval().getState().toString()).isEqualTo("REVOKED");
                assertThat(restartedChecker.checkFinancialApproval(TENANT.toString(),BOOK.toString(),approval,valid).block(Duration.ofSeconds(10)).getState().toString()).isEqualTo("REVOKED");
                sdkRefused(probes,"tamperedCheckReplay",403,()->restartedChecker.checkFinancialApproval(TENANT.toString(),BOOK.toString(),approval,changed).block(Duration.ofSeconds(10)));
                assertThat(count("firefly_audit.audit_event","resource_id",approval)).isEqualTo(3);assertThat(count("firefly_audit.audit_outbox","resource_id",approval)).isEqualTo(3);
                assertThat(count("public.gl_financial_approval_transition","approval_id",approval)).isEqualTo(3);assertThat(count("public.gl_financial_approval_mapping","approval_id",approval)).isEqualTo(1);
                assertThat(count("public.gl_native_posting","action_id",action)).isZero();
                assertThat(authority.proof().get("jwksRequests")).isInstanceOfSatisfying(Integer.class,value->assertThat(value).isPositive());
                proof.put("snapshotId",review.getSnapshotId().toString());proof.put("snapshotSha256",review.getSnapshotSha256());proof.put("auditEvents",3);proof.put("auditOutboxRows",3);proof.put("nativePostingRows",0);
            }
            proof.put("status","PASS_APPROVAL_HTTP_ONLY");
        }finally{
            proof.put("authority",authority.proof());if(main!=null)proof.put("originalMain",main.proof());
            var bytes=JSON.writeValueAsBytes(proof);assertThat(bytes.length).isLessThanOrEqualTo(64*1024);Files.write(Path.of("target/approval-http-lifecycle-proof.json"),bytes,StandardOpenOption.CREATE_NEW);
        }
    }

    private static long count(String table, String key, UUID id) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + key + "=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue();return rows.getLong(1); }
        }
    }
}
