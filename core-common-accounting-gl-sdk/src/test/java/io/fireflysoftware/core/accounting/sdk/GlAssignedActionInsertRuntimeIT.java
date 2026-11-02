/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.sdk;

import static io.fireflysoftware.core.accounting.sdk.GlNativeJournalAtomicSchemaIT.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.accounting.core.services.posting.GlPostingIntentCodec;
import io.fireflysoftware.core.accounting.models.entities.posting.GlNativeJournalAction;
import io.fireflysoftware.core.accounting.models.repositories.posting.impl.GlNativePostingRepositoryImpl;
import io.r2dbc.postgresql.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

class GlAssignedActionInsertRuntimeIT {
    static final ObjectMapper JSON=new ObjectMapper();
    static PostgreSQLContainer container;
    static String host,database,user,password,url;static int port;
    static String runtimePassword;
    static R2dbcEntityTemplate template;static GlNativePostingRepositoryImpl repository;static TransactionalOperator transaction;

    @BeforeAll static void start() throws Exception {
        String profile=System.getProperty("gl.assigned-action.test.database-profile");
        if(profile==null){
            container=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("gl_assigned_action_"+UUID.randomUUID().toString().replace("-",""))
                    .withReuse(false).withUsername("gl_migration").withPassword(UUID.randomUUID().toString()+UUID.randomUUID())
                    .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),new com.github.dockerjava.api.model.ExposedPort(5432))));
            container.start();host=container.getHost();port=container.getMappedPort(5432);database=container.getDatabaseName();user=container.getUsername();password=container.getPassword();
        }else{
            var path=Path.of(profile);assertTrue(path.isAbsolute()&&!Files.isSymbolicLink(path)&&Files.isRegularFile(path)&&Files.size(path)<=4096);
            assertTrue(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE).containsAll(Files.getPosixFilePermissions(path)));
            var p=JSON.readTree(Files.readAllBytes(path));var keys=new HashSet<String>();p.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("purpose","host","port","database","user","password"),keys);assertEquals("GL_ASSIGNED_ACTION_DISPOSABLE_TEST",p.path("purpose").asText());
            host=p.path("host").asText();port=p.path("port").asInt();database=p.path("database").asText();user=p.path("user").asText();password=p.path("password").asText();
            assertEquals("127.0.0.1",host);assertTrue(port>1024&&port<65536);assertTrue(database.matches("gl_assigned_action_[a-z0-9_]{1,32}"));assertTrue(user.matches("[a-z][a-z0-9_]{1,62}"));assertTrue(password.length()>=32);
        }
        url="jdbc:postgresql://"+host+":"+port+"/"+database;
        try(var c=owner();var s=c.createStatement()){
            try(var rs=s.executeQuery("SELECT current_setting('server_version_num')::integer/10000,(SELECT count(*) FROM pg_tables WHERE schemaname='public')")){assertTrue(rs.next());assertEquals(17,rs.getInt(1));assertEquals(0,rs.getInt(2));}
        }
        Flyway.configure().dataSource(url,user,password).locations(System.getProperty("gl.migration.location","classpath:db/migration")).load().migrate();
        runtimePassword=UUID.randomUUID().toString()+UUID.randomUUID();
        try(var c=owner();var s=c.createStatement()){
            s.execute("CREATE ROLE gl_assigned_action_runtime LOGIN PASSWORD '"+runtimePassword+"' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE;REVOKE CREATE ON SCHEMA public FROM PUBLIC;GRANT USAGE ON SCHEMA public TO gl_assigned_action_runtime;GRANT SELECT,INSERT ON public.gl_native_journal_action TO gl_assigned_action_runtime");
        }
        var factory=new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder().host(host).port(port).database(database).username("gl_assigned_action_runtime").password(runtimePassword).build());
        template=new R2dbcEntityTemplate(factory);repository=new GlNativePostingRepositoryImpl(template);transaction=TransactionalOperator.create(new R2dbcTransactionManager(factory));
        Boolean isolated=template.getDatabaseClient().sql("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit AND NOT rolcreatedb AND NOT rolcreaterole AND NOT has_schema_privilege(current_user,'public','CREATE') AND NOT pg_has_role(current_user,'gl_native_guard','MEMBER') AS isolated FROM pg_roles WHERE rolname=current_user")
                .map((r,m)->r.get("isolated",Boolean.class)).one().block(Duration.ofSeconds(5));assertEquals(Boolean.TRUE,isolated);
    }
    @AfterAll static void stop()throws Exception {
        if(container!=null){
            String id=container.getContainerId();container.stop();
            if(id!=null){
                assertThrows(com.github.dockerjava.api.exception.NotFoundException.class,
                        ()->container.getDockerClient().inspectContainerCmd(id).exec());
                String output=System.getProperty("gl.assigned-action.test.receipt");
                if(output!=null){
                    var path=Path.of(output);assertTrue(path.isAbsolute()&&!Files.exists(path));
                    Files.writeString(path,JSON.writeValueAsString(Map.of("originalContainerAbsent",true,
                            "runtimeRole","gl_assigned_action_runtime","runtimeIsNonowner",true,
                            "migrations","UNCHANGED_OWNER_MIGRATIONS","financialJourneyAccepted",false)));
                }
            }
        }
    }
    static Connection owner()throws Exception{return DriverManager.getConnection(url,user,password);}
    static GlNativeJournalAction proposed()throws Exception {
        Fixture f;Effect e;try(var c=owner()){f=fixture(c);e=completeIntentEffect(c,f,false,"1234");}
        var v=GlPostingIntentCodec.decode(f.scope().tenant().toString(),f.scope().book().toString(),e.originalRequest()).validated();
        var accounts=new UUID[]{f.debit(),f.credit()};Arrays.sort(accounts,Comparator.comparing(UUID::toString));
        return new GlNativeJournalAction(e.action(),f.scope().tenant(),f.scope().book(),"POST",v.intentDigest(),v.canonicalIntent(),v.economicIntentDigest(),v.canonicalEconomicIntent(),v.sourceClaimKey(),accounts,e.deadline(),e.receipt(),"https://synthetic.invalid","private-schema-fixture",e.deadline(),e.posting(),GlPostingIntentCodec.SHAPE,e.originalRequest());
    }
    static Mono<Void> context(GlNativeJournalAction row,UUID tenant,UUID book,UUID[] accounts,long until)throws Exception {
        return template.getDatabaseClient().sql("SELECT set_config('firefly.gl.xid',pg_current_xact_id()::text,true),set_config('firefly.gl.tenant',:tenant,true),set_config('firefly.gl.book',:book,true),set_config('firefly.gl.operation','post',true),set_config('firefly.gl.action',:action,true),set_config('firefly.gl.intent',:intent,true),set_config('firefly.gl.accounts',:accounts,true),set_config('firefly.gl.authority_until',:until,true),set_config('firefly.gl.actor_issuer',:issuer,true),set_config('firefly.gl.actor_subject',:subject,true)")
                .bind("tenant",tenant.toString()).bind("book",book.toString()).bind("action",row.actionId().toString()).bind("intent",row.intentDigest()).bind("accounts",JSON.writeValueAsString(accounts)).bind("until",Long.toString(until)).bind("issuer",row.actorIssuer()).bind("subject",row.actorSubject()).fetch().rowsUpdated().then();
    }
    static Mono<Void> context(GlNativeJournalAction row)throws Exception{return context(row,row.tenantId(),row.bookId(),row.accountIds(),row.authorityUntilEpochMillis());}
    static void absent(GlNativeJournalAction row)throws Exception {
        try(var c=owner();var s=c.prepareStatement("SELECT count(*) FROM gl_native_journal_action WHERE action_id=?")){s.setObject(1,row.actionId());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(0,r.getLong(1));}}
    }
    static class Rollback extends RuntimeException{}
    @Test void mappedInsertReadsNativeTimestampAndRollsBackEveryAction()throws Exception {
        var row=proposed();assertThrows(Rollback.class,()->transaction.transactional(context(row).then(repository.appendAction(row)).flatMap(saved->{
            assertNotSame(row,saved);assertNotNull(saved.recordedAt());assertEquals(row.actionId(),saved.actionId());assertEquals(row.originalRequest(),saved.originalRequest());assertEquals(row.receiptId(),saved.receiptId());return Mono.error(new Rollback());
        })).block(Duration.ofSeconds(10)));absent(row);
    }
    @Test void duplicateActionCannotReplayOrOverwriteTheFirstInsertion()throws Exception {
        var row=proposed();assertThrows(DuplicateKeyException.class,()->transaction.transactional(context(row).then(repository.appendAction(row)).flatMap(saved->repository.appendAction(row))).block(Duration.ofSeconds(10)));absent(row);
    }
    static void denied(GlNativeJournalAction row,UUID tenant,UUID book,UUID[] accounts,long until)throws Exception {
        var wrong=context(row,tenant,book,accounts,until);
        assertThrows(PermissionDeniedDataAccessException.class,()->transaction.transactional(context(row).then(repository.appendAction(row)).then(wrong)
                .then(repository.findAction(row.tenantId(),row.bookId(),row.actionId()).doOnNext(value->fail("Out-of-scope action disclosed")))
                .then(repository.appendAction(row))).block(Duration.ofSeconds(10)));absent(row);
    }
    @Test void wrongTenantCannotReadOrInsertTheAction()throws Exception{var r=proposed();denied(r,UUID.randomUUID(),r.bookId(),r.accountIds(),r.authorityUntilEpochMillis());}
    @Test void wrongBookCannotReadOrInsertTheAction()throws Exception{var r=proposed();denied(r,r.tenantId(),UUID.randomUUID(),r.accountIds(),r.authorityUntilEpochMillis());}
    @Test void missingAccountScopeCannotReadOrInsertTheAction()throws Exception{var r=proposed();denied(r,r.tenantId(),r.bookId(),new UUID[0],r.authorityUntilEpochMillis());}
    @Test void expiredAdmissionCannotReadOrInsertTheAction()throws Exception{var r=proposed();denied(r,r.tenantId(),r.bookId(),r.accountIds(),System.currentTimeMillis()-1000);}
}
