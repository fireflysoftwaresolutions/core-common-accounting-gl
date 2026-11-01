/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.web.openapi;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginalProducerSdkInputFixtureTest {
    @TempDir Path root;
    private Properties setup(byte[] original)throws Exception {
        var web=root.resolve("core-common-accounting-gl-web");Files.createDirectories(web.resolve("target/gl-producer"));
        var canonical=root.resolve("core-common-accounting-gl-sdk/src/main/resources/openapi.yml");Files.createDirectories(canonical.getParent());Files.write(canonical,original);
        var properties=new Properties();properties.setProperty("gl.fixture.sdk-owned-hosted","true");properties.setProperty("gl.fixture.gate","a".repeat(64));properties.setProperty("gl.fixture.sdk-source-root",root.toString());
        properties.setProperty("gl.fixture.sdk-canonical-input",canonical.toString());
        properties.setProperty("gl.fixture.sdk-canonical-preimage-sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));return properties;
    }
    @Test void explicitOwnedFixtureCopiesExactRawBytesAndRetainsThePreimageReceipt()throws Exception {
        var original="retained original input\n".getBytes();var raw="actual emitted raw YAML\n".getBytes();var properties=setup(original);
        OriginalProducerSdkInputFixtureImpl.write(raw,root.resolve("core-common-accounting-gl-web"),properties);
        assertThat(Files.readAllBytes(Path.of(properties.getProperty("gl.fixture.sdk-canonical-input")))).isEqualTo(raw);
        assertThat(Files.readString(root.resolve("core-common-accounting-gl-web/target/gl-producer/sdk-canonicalization.json"))).contains("preimageSha256","actualRawProducerSha256");
    }
    @Test void defaultOffAndWrongPreimageOrArbitraryPathCannotWriteTheCanonicalInput()throws Exception {
        var original="retained original input\n".getBytes();var properties=setup(original);var web=root.resolve("core-common-accounting-gl-web");
        OriginalProducerSdkInputFixtureImpl.write("unrequested".getBytes(),web,new Properties());
        var target=Path.of(properties.getProperty("gl.fixture.sdk-canonical-input"));assertThat(Files.readAllBytes(target)).isEqualTo(original);
        var missingMarker=(Properties)properties.clone();missingMarker.remove("gl.fixture.sdk-owned-hosted");
        assertThatThrownBy(()->OriginalProducerSdkInputFixtureImpl.write("bad".getBytes(),web,missingMarker)).isInstanceOf(IllegalStateException.class);
        properties.setProperty("gl.fixture.sdk-canonical-preimage-sha256","b".repeat(64));
        assertThatThrownBy(()->OriginalProducerSdkInputFixtureImpl.write("bad".getBytes(),web,properties)).isInstanceOf(IllegalStateException.class);
        properties.setProperty("gl.fixture.sdk-canonical-input",root.resolve("unowned.yml").toString());
        assertThatThrownBy(()->OriginalProducerSdkInputFixtureImpl.write("bad".getBytes(),web,properties)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(original);assertThat(root.resolve("unowned.yml")).doesNotExist();
    }
    @Test void aSymlinkCannotRedirectEvenTheDeclaredCanonicalAlias()throws Exception {
        var original="retained original input\n".getBytes();var properties=setup(original);var target=Path.of(properties.getProperty("gl.fixture.sdk-canonical-input"));
        var outside=root.resolve("retained.yml");Files.write(outside,original);Files.delete(target);Files.createSymbolicLink(target,outside);
        assertThatThrownBy(()->OriginalProducerSdkInputFixtureImpl.write("bad".getBytes(),root.resolve("core-common-accounting-gl-web"),properties)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(outside)).isEqualTo(original);
    }
}
