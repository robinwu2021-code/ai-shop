package ai.neargo.commerce.core.spi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Stable UTF-8 fields, length-prefixed to distinguish boundaries, null and empty. */
public final class FactDigest {
    private FactDigest() {}

    public static String of(List<String> fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var field : fields) {
                byte[] bytes = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
