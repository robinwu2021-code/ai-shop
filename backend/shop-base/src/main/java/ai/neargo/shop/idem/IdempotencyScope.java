package ai.neargo.shop.idem;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Strict opt-in contract for new retail/food/beauty commands; legacy executor overloads remain unchanged. */
public final class IdempotencyScope {
    private IdempotencyScope() {
    }

    /** Keeps client keys byte-for-byte while rejecting commands that would bypass durable idempotency. */
    public static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return key;
    }

    /** Industry is a fixed server constant; length-prefixed components cannot collide through delimiters. */
    public static String endpoint(String industry, String operation, String entityNo, String storeNo) {
        if (industry == null || !industry.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (String field : new String[]{operation, entityNo, storeNo}) {
            if (field == null || field.isBlank()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        return industry + ":v1:" + requestHash(operation, entityNo, storeNo);
    }

    /** Hashes an explicit typed semantic field sequence; callers own canonical field/value normalization. */
    public static String requestHash(String... fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                for (String field : fields) {
                    if (field == null) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    byte[] value = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(value.length);
                    output.write(value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot encode idempotency contract", ex);
        }
    }
}
