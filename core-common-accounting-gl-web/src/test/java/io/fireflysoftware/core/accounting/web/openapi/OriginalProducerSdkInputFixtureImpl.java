/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit hosted-fixture canonicalization after the real producer assertions, before SDK generation. */
final class OriginalProducerSdkInputFixtureImpl {
    private OriginalProducerSdkInputFixtureImpl() {}
    static void writeIfRequested(byte[] raw)throws Exception {
        write(raw,Path.of("").toAbsolutePath(),System.getProperties());
    }
    static void write(byte[] raw,Path web,Properties properties)throws Exception {
        String supplied=properties.getProperty("gl.fixture.sdk-canonical-input");if(supplied==null)return;
        require("true".equals(properties.getProperty("gl.fixture.sdk-owned-hosted")),"Explicit supervisor-verified owned hosted fixture required");
        String gate=properties.getProperty("gl.fixture.gate"),preimage=properties.getProperty("gl.fixture.sdk-canonical-preimage-sha256");
        require(gate!=null&&gate.matches("[0-9a-f]{64}")&&preimage!=null&&preimage.matches("[0-9a-f]{64}"),"Pinned source gate and canonical preimage required");
        require(raw!=null&&raw.length>0&&raw.length<=4*1024*1024,"Bounded original raw producer input required");
        Path root=Path.of(properties.getProperty("gl.fixture.sdk-source-root","")).toAbsolutePath().normalize();
        require(root.equals(web.toAbsolutePath().normalize().getParent())&&root.equals(root.toRealPath()),"Exact verified owner source root required");
        Path target=Path.of(supplied);require(target.isAbsolute(),"Absolute owned canonical input required");target=target.normalize();
        require(target.equals(root.resolve("core-common-accounting-gl-sdk/src/main/resources/openapi.yml")),"Only the existing owner canonical alias may change");
        for(Path path=target;path!=null;path=path.getParent())require(!Files.isSymbolicLink(path),"Nonsymlink owner canonical input required");
        require(Files.isRegularFile(target)&&Files.size(target)<=4*1024*1024&&sha(Files.readAllBytes(target)).equals(preimage),"Exact frozen canonical preimage required");
        Path temporary=target.resolveSibling(".openapi-"+gate+".tmp");Files.write(temporary,raw,StandardOpenOption.CREATE_NEW);
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        require(Arrays.equals(Files.readAllBytes(target),raw),"Exact original producer bytes required");
        Path receipt=web.resolve("target/gl-producer/sdk-canonicalization.json");
        Files.writeString(receipt,new ObjectMapper().writeValueAsString(Map.of("schema","GL_ORIGINAL_PRODUCER_CANONICALIZATION_V1",
                "sourceGate",gate,"relativeInput","core-common-accounting-gl-sdk/src/main/resources/openapi.yml",
                "preimageSha256",preimage,"actualRawProducerSha256",sha(raw),"bytes",raw.length))+"\n",StandardOpenOption.CREATE_NEW);
    }
    private static String sha(byte[] body)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
