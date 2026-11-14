package ai.neargo.shop.trade.order.service.create;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic owner evidence, never a digest of low-entropy contact data. */
public final class CheckoutDigests {
    private CheckoutDigests() {}

    /** Canonicalize map ordering so equivalent frozen evidence yields the same SHA-256 digest. */
    public static String hash(ObjectMapper json, Object value) {
        return hash(
                json.writer()
                        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsString(value));
    }

    /**
     * Hash high-entropy references or canonical evidence without retaining reusable credentials.
     */
    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
