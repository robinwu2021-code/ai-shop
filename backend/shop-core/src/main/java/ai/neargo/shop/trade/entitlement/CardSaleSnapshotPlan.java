package ai.neargo.shop.trade.entitlement;

import ai.neargo.shop.spi.entitlement.SourceRef;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Builds immutable paid-card rights identities from trusted checkout snapshots. */
public final class CardSaleSnapshotPlan {
    private CardSaleSnapshotPlan() { }

    public static List<Snapshot> expand(OrderFacts order, List<SaleLine> lines) {
        if (order == null || lines == null || lines.isEmpty()) throw new IllegalArgumentException("Sale facts required");
        var result = new ArrayList<Snapshot>();
        for (SaleLine line : lines) {
            if (line == null || blank(line.orderLineNo()) || blank(line.subOrderNo()) || blank(line.goodsNo())
                    || blank(line.skuNo()) || line.unitPriceMinor() < 0 || line.quantity() <= 0
                    || line.entitlements() == null || line.entitlements().isEmpty()) {
                throw new IllegalArgumentException("Sale line facts are incomplete");
            }
            for (int unit = 1; unit <= line.quantity(); unit++) {
                for (int index = 0; index < line.entitlements().size(); index++) {
                    EntitlementRule rule = line.entitlements().get(index);
                    if (rule == null || blank(rule.templateVersionNo()) || blank(rule.ruleType()) || blank(rule.ruleSnapshot())) {
                        throw new IllegalArgumentException("Entitlement snapshot is incomplete");
                    }
                    var source = new SourceRef("CORE", "PAID_CARD_ORDER", order.orderNo(), line.orderLineNo(),
                            unit, index + 1, 0);
                    String hash = sha256(order.orderNo(), order.userNo(), order.entityNo(), order.storeNo(),
                            order.currency(), line.orderLineNo(), line.subOrderNo(), line.goodsNo(), line.skuNo(),
                            Integer.toString(unit), Integer.toString(index + 1), rule.templateVersionNo(),
                            Long.toString(line.unitPriceMinor()), rule.ruleType(), rule.ruleSnapshot());
                    result.add(new Snapshot(source, order, line, unit, index + 1, rule, hash));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Internal stable line identity; the persisted ord_item row id never leaves the core boundary. */
    public static String orderLineIdentity(long persistedOrderItemId) {
        if (persistedOrderItemId <= 0) throw new IllegalArgumentException("Persisted order line id is required");
        return "OL" + persistedOrderItemId;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String sha256(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is required", e); }
    }

    public record OrderFacts(String orderNo, String userNo, String entityNo, String storeNo, String currency) {
        public OrderFacts {
            if (blank(orderNo) || blank(userNo) || blank(entityNo) || blank(storeNo) || blank(currency))
                throw new IllegalArgumentException("Order identity is incomplete");
        }
    }
    public record SaleLine(String orderLineNo, String subOrderNo, String goodsNo, String skuNo,
                           long unitPriceMinor, int quantity, List<EntitlementRule> entitlements) { }
    public record EntitlementRule(String templateVersionNo, String ruleType, String ruleSnapshot) { }
    public record Snapshot(SourceRef source, OrderFacts order, SaleLine line, int unitIndex,
                           int entitlementIndex, EntitlementRule rule, String snapshotHash) { }
}
