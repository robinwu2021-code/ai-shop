package ai.neargo.inventory.domain.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * 宿主来源行（SKU / 门店 / 履约单）与它们的规范清单哈希。
 *
 * <p><b>哈希必须与宿主算出来的逐字相同</b>：预留上冻结的 {@code source_manifest_hash} 由商城下单时算、
 * 库存这边校验，两边差一个字节就是「原单对不上」。算法照抄 ai-shop 的 {@code StockDispositionContract}
 * （{@code STOCK_MANIFEST_V1}，长度前缀字段），{@code DomainRuleParityTest} 逐例比对两份实现。
 */
public final class SourceStock {

    private SourceStock() {
    }

    /** 一行来源：与商城 {@code StockPort.SkuQty} 同形 */
    public record Qty(String skuNo, int qty, String storeNo, String fulfillmentRef, int lifecycleVersion) {
        public Qty(String skuNo, int qty, String storeNo) {
            this(skuNo, qty, storeNo, null, 1);
        }
    }

    public record Need(String skuNo, String sourceStoreNo, String fulfillmentRef, int quantity) {
    }

    /** 同一 (SKU, 门店, 履约单) 合并数量，按键排序 */
    public static List<Need> needs(List<Qty> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("empty stock manifest");
        }
        var values = new TreeMap<String, Need>();
        for (var line : source) {
            var need = new Need(line.skuNo(), line.storeNo(), line.fulfillmentRef(), line.qty());
            String key = field(need.skuNo()) + field(need.sourceStoreNo()) + field(need.fulfillmentRef());
            values.merge(key, need, (a, b) -> new Need(a.skuNo(), a.sourceStoreNo(), a.fulfillmentRef(),
                    Math.addExact(a.quantity(), b.quantity())));
        }
        return values.values().stream()
                .sorted(Comparator.comparing(Need::skuNo)
                        .thenComparing(n -> field(n.sourceStoreNo()))
                        .thenComparing(Need::fulfillmentRef))
                .toList();
    }

    public static String manifestHash(List<Need> needs) {
        if (needs == null || needs.isEmpty()) {
            throw new IllegalArgumentException("empty stock manifest");
        }
        var canonical = needs(needs.stream()
                .map(n -> new Qty(n.skuNo(), n.quantity(), n.sourceStoreNo(), n.fulfillmentRef(), 1)).toList());
        var value = new StringBuilder("STOCK_MANIFEST_V1");
        for (var n : canonical) {
            value.append(field(n.skuNo())).append(field(n.sourceStoreNo())).append(field(n.fulfillmentRef()))
                    .append(field(Integer.toString(n.quantity())));
        }
        return hash(value.toString());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }
}
