package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** Canonical full source manifest shared by the actual stock owners and the order creator. */
public final class StockDispositionContract {
    private StockDispositionContract() {}

    /**
     * Actual selected reservation adapters identify themselves without reading mutable
     * configuration.
     */
    public interface ReservationOwner {
        String stockAuthority();
    }

    /** Aggregate repeated tuples exactly, keeping distinct fulfillment/store allocations. */
    public static List<Need> needs(List<StockPort.SkuQty> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("empty stock manifest");
        }
        var values = new TreeMap<String, Need>();
        for (var line : source) {
            var need = new Need(line.skuNo(), line.storeNo(), line.fulfillmentRef(), line.qty());
            String key =
                    field(need.skuNo())
                            + field(need.sourceStoreNo())
                            + field(need.fulfillmentRef());
            values.merge(
                    key,
                    need,
                    (a, b) ->
                            new Need(
                                    a.skuNo(),
                                    a.sourceStoreNo(),
                                    a.fulfillmentRef(),
                                    Math.addExact(a.quantity(), b.quantity())));
        }
        return values.values().stream()
                .sorted(
                        Comparator.comparing(Need::skuNo)
                                .thenComparing(n -> field(n.sourceStoreNo()))
                                .thenComparing(Need::fulfillmentRef))
                .toList();
    }

    /** Hash length-prefixed fields so delimiters and null never alias a different tuple. */
    public static String manifestHash(List<Need> needs) {
        if (needs == null || needs.isEmpty()) {
            throw new IllegalArgumentException("empty stock manifest");
        }
        var canonical =
                needs(
                        needs.stream()
                                .map(
                                        n ->
                                                new StockPort.SkuQty(
                                                        n.skuNo(),
                                                        n.quantity(),
                                                        n.sourceStoreNo(),
                                                        n.fulfillmentRef(),
                                                        1))
                                .toList());
        var value = new StringBuilder("STOCK_MANIFEST_V1");
        for (var n : canonical) {
            value.append(field(n.skuNo()))
                    .append(field(n.sourceStoreNo()))
                    .append(field(n.fulfillmentRef()))
                    .append(field(Integer.toString(n.quantity())));
        }
        return hash(value.toString());
    }

    /** Stable command identity survives retries and current provider configuration changes. */
    public static String operationNo(Original original) {
        return hash(
                "STOCK_CONFIRM_V1"
                        + field(original.tenantNo())
                        + field(original.orderNo())
                        + field(original.authority())
                        + field(Integer.toString(original.lifecycleVersion()))
                        + field(original.manifestHash()));
    }

    public static String reservationOperationNo(Original original) {
        validate(original);
        return hash(
                "STOCK_RESERVE_V1"
                        + field(original.tenantNo())
                        + field(original.entityNo())
                        + field(original.orderNo())
                        + field(original.authority())
                        + field(Integer.toString(original.lifecycleVersion()))
                        + field(original.manifestHash()));
    }

    public static void requireReservationOperation(Original original, String operation) {
        if (original == null || !reservationOperationNo(original).equals(operation)) {
            throw new IllegalArgumentException("reservation operation does not match original");
        }
    }

    /** Validate complete frozen owner coordinates before any IO. */
    public static void validate(Original original) {
        if (!"MAIN".equals(original.tenantNo())
                || original.entityNo() == null
                || original.entityNo().isBlank()
                || original.orderNo() == null
                || original.orderNo().isBlank()
                || !List.of("PLATFORM", "DUAL", "INVENTORY").contains(original.authority())
                // Credit issue/acceptance exists only at the native Inventory owner.
                || original.lifecycleVersion() != 1 && original.lifecycleVersion() != 2
                        && !(original.lifecycleVersion() == 3 && "INVENTORY".equals(original.authority()))
                || !manifestHash(original.needs()).equals(original.manifestHash())) {
            throw new IllegalArgumentException("invalid original stock manifest");
        }
    }

    public static void requireOperation(Original original, String operationNo) {
        if (original == null || !operationNo(original).equals(operationNo)) {
            throw new IllegalArgumentException("stock operation does not match original");
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }
}
