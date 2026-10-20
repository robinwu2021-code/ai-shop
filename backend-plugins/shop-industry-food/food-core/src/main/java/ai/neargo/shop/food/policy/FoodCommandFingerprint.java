package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.CreatePolicyReq;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Length-prefixed semantic fields avoid raw-JSON ordering and ambiguous operation scope concatenation. */
public final class FoodCommandFingerprint {
    private FoodCommandFingerprint() { }

    /** Fits the existing endpoint column while keeping store and entity boundaries unambiguous. */
    public static String scope(String operation, String entityNo, String storeNo) {
        return "FOOD:v1:" + hash(operation, entityNo, storeNo);
    }

    /** Includes every accepted publication parameter; blank approval is semantically absent. */
    public static String policy(CreatePolicyReq req) {
        var capabilities = req.capabilities().stream().distinct().sorted().toList();
        var fields = new ArrayList<>(List.of("policy:v1", req.timezone(), req.cutoverMinute().toString(),
                req.effectiveFrom().toString(), req.payPolicy(), Integer.toString(capabilities.size())));
        fields.addAll(capabilities);
        fields.add(req.approvalRef() == null || req.approvalRef().isBlank() ? "" : req.approvalRef());
        fields.add(req.version().toString());
        return hash(fields.toArray(String[]::new));
    }

    /** Hashes audit correlation identifiers without persisting raw client keys as request numbers. */
    public static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
