package ai.neargo.shop.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** MySQL SHA2 semantics for the generated H2 fixture; native migrations remain unchanged. */
public final class H2HashFunctions {
    private H2HashFunctions() {}

    public static String sha2(String value, Integer bits) throws NoSuchAlgorithmException {
        if (value == null || bits == null) return null;
        String algorithm = switch (bits) {
            case 0, 256 -> "SHA-256";
            case 224 -> "SHA-224";
            case 384 -> "SHA-384";
            case 512 -> "SHA-512";
            default -> null;
        };
        return algorithm == null ? null : HexFormat.of().formatHex(
                MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
