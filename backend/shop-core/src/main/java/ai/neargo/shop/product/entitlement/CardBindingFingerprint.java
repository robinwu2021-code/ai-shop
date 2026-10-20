package ai.neargo.shop.product.entitlement;

import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

public final class CardBindingFingerprint {
    private CardBindingFingerprint() { }
    public static String payload(EntitlementContext ctx, String entity, ProductEntitlementBinding c, String key,
                                 ObjectMapper json) {
        ContractChecks.id(key);
        try {
            String value = json.writeValueAsString(List.of(ctx.actorNo(), entity, c));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 required", impossible);
        }
    }
}
