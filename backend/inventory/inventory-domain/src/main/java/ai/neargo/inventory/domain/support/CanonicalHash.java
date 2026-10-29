package ai.neargo.inventory.domain.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 快照的规范化摘要：键排序、小数去尾零，再 SHA-256。
 *
 * <p><b>输出必须与 ai-shop 的 {@code PricingSnapshotHash} 逐字节相同</b> —— 库里存着按它算出的
 * 摘要（估值快照、按需计划），换一个实现算出不同的值，历史记录就全部「对不上」了。
 * {@code shop-inventory} 的 {@code DomainRuleParityTest} 用同一批输入比对两边。
 */
public final class CanonicalHash {

    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                            .maxStringLength(256).maxNameLength(64).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    private CanonicalHash() {
    }

    public static String of(Object value) {
        String json = JSON.writeValueAsString(normalize(JSON.valueToTree(value)));
        if (json.getBytes(StandardCharsets.UTF_8).length > 2097152) {
            throw new IllegalArgumentException("Bounded source snapshot required");
        }
        return sha256("PRICING_SOURCE_V1\n" + json);
    }

    private static Object normalize(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), normalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<Object>();
            node.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node.isNumber() && !node.isIntegralNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            return number.scale() < 0 ? number.setScale(0) : number;
        }
        return node;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
