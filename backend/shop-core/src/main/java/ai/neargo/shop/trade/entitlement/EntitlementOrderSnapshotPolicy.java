package ai.neargo.shop.trade.entitlement;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Authorization and source-owner preflight hash the exact same sorted order snapshot. */
public final class EntitlementOrderSnapshotPolicy {
    private EntitlementOrderSnapshotPolicy() { }
    public static String hash(ObjectMapper json, OrdOrder order, List<OrdSubOrder> subs, List<OrdItem> items) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json.writeValueAsString(List.of(order, subs, items)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException absent) { throw new IllegalStateException("SHA-256 required", absent); }
    }
}
