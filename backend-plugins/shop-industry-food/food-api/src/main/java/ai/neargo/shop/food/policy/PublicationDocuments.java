package ai.neargo.shop.food.policy;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Canonical semantic hashing survives MySQL JSON key/number normalization without losing decimal precision. */
public final class PublicationDocuments {
    private PublicationDocuments() { }
    /** Decimal quantities are strings in schema one: native JSON floating point must not round DECIMAL18,6. */
    public static String freeze(ObjectMapper json, PublicationSnapshot value) {
        Object tree = json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(json.writeValueAsString(value));
        return json.writeValueAsString(quantities(tree));
    }
    private static Object quantities(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>();
            map.forEach((key, item) -> sorted.put((String) key,
                    Set.of("minQty", "maxQty", "defaultQty", "quantity").contains(key) && item instanceof Number number
                            ? new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() : quantities(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(PublicationDocuments::quantities).toList();
        return value;
    }
    public static String hash(ObjectMapper json, String document) {
        Object tree = json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(document);
        String canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(normalize(tree));
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("config-publication:v1", canonical)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static Object normalize(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>();
            map.forEach((key, item) -> sorted.put((String) key, normalize(item))); return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(PublicationDocuments::normalize).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
}
