package ai.neargo.shop.spi.food;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;

/** Shared canonical fingerprints; mutable headers and the claimed hash are excluded. */
public final class FoodStockFingerprint {
    private FoodStockFingerprint() {}
    public static String request(ObjectMapper json,String action,FoodLocalStockPort.Command c,Object payload) {
        return sha256(json.writeValueAsString(new Object[]{action,c.operationKey(),c.sourceSystem(),c.sourceNo(),
                c.sourceVersion(),c.lifecycleVersion(),payload}));
    }
    public static String plan(ObjectMapper json,FoodLocalStockPort.Plan plan) {
        return sha256(json.writeValueAsString(new Object[]{plan.expiresAt(),plan.sourcePlanHash(),plan.protocolVersion(),plan.leaves().stream()
                .sorted(Comparator.comparing(FoodLocalStockPort.Leaf::leafRef)).toList()}));
    }
    public static String sha256(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
