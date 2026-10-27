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

package io.fireflysoftware.core.accounting.core.services.posting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public final class GlBookIdentity {
    public static final UUID NAMESPACE = UUID.fromString("d28580cc-de78-53fd-99c9-2f1dd076c546");

    private GlBookIdentity() {}

    /** Stable owner-assigned identity before approval; this UUID establishes no authority. */
    public static String id(String tenantId, String enrollmentActionId) {
        GlPostingRules.uuid(tenantId);
        GlPostingRules.uuid(enrollmentActionId);
        try {
            var sha = MessageDigest.getInstance("SHA-1");
            sha.update(
                    ByteBuffer.allocate(16)
                            .putLong(NAMESPACE.getMostSignificantBits())
                            .putLong(NAMESPACE.getLeastSignificantBits())
                            .array());
            var bytes =
                    sha.digest(
                            ("GL_BOOK_ID_V1\n" + tenantId + "\n" + enrollmentActionId)
                                    .getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var uuid = ByteBuffer.wrap(bytes);
            return new UUID(uuid.getLong(), uuid.getLong()).toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
