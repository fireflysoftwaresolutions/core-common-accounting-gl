/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.sdk.impl;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Boots the unchanged executable Main against the already owned restricted approval database. */
public final class GlApprovalMainProcessFixtureImpl implements AutoCloseable {
    private static final String CACHE_DB="synthetic_gl_approval_http_cache",CACHE_USER="gl_approval_http_cache";
    private Process process;
    private final ProcessBuilder builder;
    private final int port;
    private final Map<Integer,Integer> statuses=new TreeMap<>();
    private int attempts;
    private int startups=1;

    public GlApprovalMainProcessFixtureImpl(String host,int databasePort,String database,String installer,String installerPassword,
            String runtimePassword,GlApprovalHttpAuthorityFixtureImpl authority,String approvalIssuer)throws Exception {
        if(!database.matches("[a-z_][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Owned synthetic database name required");
        byte[] entropy=new byte[32];new java.security.SecureRandom().nextBytes(entropy);String cachePassword=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String jdbc="jdbc:postgresql://"+host+":"+databasePort+"/";
        try(var connection=DriverManager.getConnection(jdbc+database,installer,installerPassword);var sql=connection.createStatement()){
            sql.execute("REVOKE CONNECT ON DATABASE "+database+" FROM PUBLIC;GRANT CONNECT ON DATABASE "+database+" TO firefly_audit_writer");
            sql.execute("CREATE ROLE "+CACHE_USER+" LOGIN PASSWORD '"+cachePassword+"' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE NOREPLICATION");
            sql.execute("CREATE DATABASE "+CACHE_DB);
        }
        try(var connection=DriverManager.getConnection(jdbc+CACHE_DB,installer,installerPassword);var sql=connection.createStatement()){
            sql.execute("REVOKE CONNECT ON DATABASE "+CACHE_DB+" FROM PUBLIC;GRANT CONNECT ON DATABASE "+CACHE_DB+" TO "+CACHE_USER+";"
                    +"REVOKE CREATE ON SCHEMA public FROM PUBLIC;CREATE SCHEMA firefly_gl_cache;"
                    +"CREATE TABLE firefly_gl_cache.cache_entries(cache_name VARCHAR(255) NOT NULL,cache_key VARCHAR(1024) NOT NULL,value BYTEA NOT NULL,"
                    +"expires_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(cache_name,cache_key));"
                    +"CREATE INDEX cache_entries_expiry ON firefly_gl_cache.cache_entries(expires_at) WHERE expires_at IS NOT NULL;"
                    +"GRANT USAGE ON SCHEMA firefly_gl_cache TO "+CACHE_USER+";GRANT SELECT,INSERT,UPDATE,DELETE ON firefly_gl_cache.cache_entries TO "+CACHE_USER);
        }
        crossDatabaseDenied(jdbc+database,CACHE_USER,cachePassword);
        crossDatabaseDenied(jdbc+CACHE_DB,"firefly_audit_writer",runtimePassword);
        try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var log=Path.of("target/approval-gl-native.log");Files.createFile(log);
        Files.setPosixFilePermissions(log,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-jar",
                Path.of("../core-common-accounting-gl-web/target/core-common-accounting-gl.jar").toAbsolutePath().toString(),
                "--server.address=127.0.0.1","--server.port="+port,"--DB_HOST="+host,"--DB_PORT="+databasePort,"--DB_NAME="+database,
                "--DB_USERNAME=firefly_audit_writer","--DB_SSL_MODE=disable","--CACHE_DB_HOST="+host,"--CACHE_DB_PORT="+databasePort,
                "--CACHE_DB_NAME="+CACHE_DB,"--CACHE_DB_USERNAME="+CACHE_USER,"--CACHE_DB_SSL_MODE=disable","--CACHE_DB_SCHEMA=firefly_gl_cache",
                "--spring.flyway.enabled=false","--firefly.eda.enabled=false","--firefly.stepevents.enabled=false",
                "--firefly.accounting.native-posting.enabled=true","--firefly.accounting.financial-approval.enabled=true",
                "--firefly.accounting.financial-approval.identity-issuer="+authority.issuer(),"--firefly.accounting.financial-approval.approval-issuer="+approvalIssuer,
                "--firefly.accounting.financial-approval.client-claim=azp","--firefly.accounting.financial-approval.canonical-staff-realm=synthetic-staff",
                "--firefly.accounting.financial-approval.maximum-business-lifetime=15m","--firefly.accounting.approval.trusted-issuers="+approvalIssuer,
                "--firefly.security.resource-server.strict-policy=true","--firefly.security.resource-server.issuer="+authority.issuer(),
                "--firefly.security.resource-server.jwk-set-uri="+authority.baseUrl()+"/jwks","--firefly.security.resource-server.audiences="+GlApprovalHttpAuthorityFixtureImpl.AUDIENCE,
                "--firefly.security.resource-server.tenant-claim-paths=tenant","--firefly.security.resource-server.tenant-required=true",
                "--firefly.security.resource-server.scope-claim-paths=scope","--firefly.accounting.authorization.opa.enabled=true",
                "--firefly.accounting.authorization.opa.base-url="+authority.baseUrl(),"--firefly.accounting.authorization.opa.allow-loopback-http=true",
                "--firefly.accounting.authorization.opa.decision-path=gl-approval/allow","--firefly.accounting.authorization.opa.evaluation-path=gl-approval/current")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("DB_PASSWORD",runtimePassword);builder.environment().put("CACHE_DB_PASSWORD",cachePassword);process=builder.start();
    }
    public String baseUrl(){return "http://127.0.0.1:"+port;}
    public void ready(String token)throws Exception {
        Instant until=Instant.now().plusSeconds(45);var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for(int n=0;n<160&&Instant.now().isBefore(until);n++){
            attempts++;if(!process.isAlive())throw new IllegalStateException("Owned approval Main exited");
            try{
                var response=client.send(HttpRequest.newBuilder(URI.create(baseUrl()+"/v3/api-docs")).timeout(Duration.ofSeconds(3))
                        .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var body=response.body()){
                    if(body.readNBytes(4*1024*1024+1).length>4*1024*1024)throw new IllegalStateException("Bounded approval producer document required");
                }
                statuses.merge(response.statusCode(),1,Integer::sum);if(response.statusCode()==200)return;
            }catch(java.io.IOException unavailable){statuses.merge(-1,1,Integer::sum);}
            Thread.sleep(250);
        }
        throw new IllegalStateException("Owned approval Main readiness timeout");
    }
    public Map<String,Object> proof(){return Map.of("schema","GL_APPROVAL_ORIGINAL_MAIN_V1","main","io.fireflysoftware.core.accounting.web.GeneralLedgerApplication",
            "command","java -jar original packaged GL application","runtimeRole","firefly_audit_writer","technicalCacheSeparateDatabase",CACHE_DB,
            "readinessAttempts",attempts,"readinessStatuses",Map.copyOf(statuses),"originalMainStarts",startups,"ledgerSourceProviderConfigured",false);}
    public void restart(String token)throws Exception {
        terminate();long previous=process.pid();process=builder.start();startups++;
        if(process.pid()==previous)throw new IllegalStateException("A fresh original Main process required");ready(token);
    }
    @Override public void close()throws Exception {
        terminate();
    }
    private void terminate()throws Exception {
        process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Owned approval Main termination required");}
    }
    private static void crossDatabaseDenied(String jdbc,String user,String password)throws Exception {
        try(var ignored=DriverManager.getConnection(jdbc,user,password)){throw new IllegalStateException("Independent technical/business database access required");}
        catch(java.sql.SQLException denied){if(!"42501".equals(denied.getSQLState()))throw denied;}
    }
    @Override public String toString(){return "GlApprovalMainProcessFixtureImpl[redacted]";}
}
