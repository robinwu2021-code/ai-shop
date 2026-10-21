package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Canonical semantic hashing survives MySQL JSON key/number normalization without losing decimal precision. */
public final class PublicationDocuments {
    private PublicationDocuments() { }
    /** Decimal quantities are strings in schema one: native JSON floating point must not round DECIMAL18,6. */
    public static String freeze(ObjectMapper json, PublicationSnapshot value) {
        return freezeValue(json, value);
    }
    /** Bundle format two adds exact unitsPerPick without rewriting the original schema-one document. */
    public static String freeze(ObjectMapper json, BundlePublicationSnapshot value) {
        return freezeValue(json, value);
    }
    public static String freeze(ObjectMapper json, RecipePublicationSnapshot value) {
        return freezeValue(json, value);
    }
    private static String freezeValue(ObjectMapper json, Object value) {
        Object tree = json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(json.writeValueAsString(value));
        return json.writeValueAsString(quantities(tree));
    }
    private static Object quantities(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>();
            map.forEach((key, item) -> sorted.put((String) key,
                    Set.of("minQty", "maxQty", "defaultQty", "quantity", "unitsPerPick",
                            "qty", "yieldQty", "lossRate").contains(key)
                            && item instanceof Number number
                            ? new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() : quantities(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(PublicationDocuments::quantities).toList();
        return value;
    }
    /** Validates known schema/kind pairs; callers compare the returned schema with persisted metadata when present. */
    public static int validate(ObjectMapper json, String kind, String number, String document) {
        try {
            Map<?,?> envelope = json.readValue(document, Map.class);
            Object schema = envelope.get("contentSchema");
            if (!(schema instanceof Integer version) || !Objects.equals(kind, envelope.get("configKind"))
                    || !Objects.equals(number, envelope.get("configNo"))) throw malformed();
            if (version == 1 && Set.of("MODIFIER", "OPTION_GROUP").contains(kind)) {
                var body = json.readValue(document, PublicationSnapshot.class);
                if (body.sourceVersion() < 0 || "MODIFIER".equals(kind)
                        && (body.modifier() == null || body.optionGroup() != null)
                        || "OPTION_GROUP".equals(kind) && (body.optionGroup() == null || body.modifier() != null)) {
                    throw malformed();
                }
                return 1;
            }
            if (version == 3 && "RECIPE".equals(kind)) {
                RecipePublicationDocuments.validate(json, number, document);
                return 3;
            }
            if (version != 2 || !"BUNDLE".equals(kind)) throw malformed();
            exactQuantities(envelope);
            var body = json.readValue(document, BundlePublicationSnapshot.class);
            var bundle = body.bundle();
            if (bundle == null || !number.equals(bundle.configNo()) || body.sourceVersion() != bundle.version()
                    || bundle.data() == null || bundle.data().configVersion() < 0
                    || bundle.data().groups().isEmpty() || bundle.data().groups().size() > 30
                    || bundle.data().choices().isEmpty() || bundle.data().choices().size() > 100) throw malformed();
            var expected = new HashSet<String>(); expected.add(number);
            bundle.data().groups().forEach(g -> {
                if (g.baseSkuNo() != null) expected.add(g.baseSkuNo());
            });
            bundle.data().choices().forEach(c -> expected.add(c.skuNo()));
            var actual = new HashSet<String>();
            for (var fact : body.skuFacts()) {
                if (fact == null || !key(fact.skuNo()) || !key(fact.goodsNo()) || !key(fact.entityNo())
                        || !key(fact.storeNo()) || fact.version() < 0 || !actual.add(fact.skuNo())) throw malformed();
            }
            if (!actual.equals(expected)) throw malformed();
            return 2;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid frozen configuration document", failure);
        }
    }
    private static void exactQuantities(Object value) {
        if (value instanceof Map<?,?> map) {
            map.forEach((key, item) -> {
                if (Set.of("minQty", "maxQty", "defaultQty", "unitsPerPick").contains(key)) {
                    if (!(item instanceof String text) || text.length() > 24
                            || !text.matches("[0-9]+(?:\\.[0-9]+)?")) throw malformed();
                    var decimal = new BigDecimal(text);
                    boolean units = "unitsPerPick".equals(key);
                    if (decimal.scale() > (units ? 3 : 6) || units && decimal.signum() == 0
                            || decimal.compareTo(new BigDecimal(units ? "999999999.999"
                            : "999999999999.999999")) > 0) throw malformed();
                } else exactQuantities(item);
            });
        } else if (value instanceof List<?> list) list.forEach(PublicationDocuments::exactQuantities);
    }
    private static boolean key(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.equals(value.trim());
    }
    private static IllegalStateException malformed() {
        return new IllegalStateException("Unsupported or malformed frozen configuration");
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
