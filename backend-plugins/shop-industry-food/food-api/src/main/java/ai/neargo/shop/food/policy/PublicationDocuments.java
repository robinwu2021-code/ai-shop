package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.StationPublicationSnapshot;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationMarketContext;
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
    public static String freeze(ObjectMapper json, StationPublicationSnapshot value) {
        return freezeValue(json, value);
    }
    public static String freeze(ObjectMapper json, MenuPublicationSnapshot value) {
        return MenuPublicationDocuments.freeze(json, value);
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
            if (version == 6) {
                validateVersionNumbers(envelope);
                json = PublicationJsonPolicy.strict(json);
            }
            if ((version == 1 || version == 6) && Set.of("MODIFIER", "OPTION_GROUP").contains(kind)) {
                PublicationSnapshot body = version == 6 ? json.readerFor(PublicationSnapshot.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(document)
                        : json.readValue(document, PublicationSnapshot.class);
                PublicationMarketPolicy.validateSchema(version, 1, body.marketContext());
                if (version == 6) {
                    Object source = envelope.get("sourceVersion");
                    if (!(source instanceof Integer || source instanceof Long)
                            || ((Number) source).longValue() < 0) throw malformed();
                    Object definition = envelope.get("MODIFIER".equals(kind) ? "modifier" : "optionGroup");
                    if (!(definition instanceof Map<?, ?> fields)
                            || !fields.containsKey("MODIFIER".equals(kind) ? "version" : "revision")) throw malformed();
                    exactQuantities(envelope);
                    PublicationMarketPolicy.validateSkuFacts(body.marketContext(), body.skuFacts());
                    PublicationMarketPolicy.validateSpecFacts(body.marketContext(), body.specFacts());
                    if ("MODIFIER".equals(kind)) {
                        if (body.modifier() == null || body.modifier().data() == null
                                || !number.equals(body.modifier().configNo())
                                || body.sourceVersion() != body.modifier().version()) throw malformed();
                        validateModifierSkuFacts(body);
                    } else if (body.optionGroup() == null || body.optionGroup().data() == null
                            || !number.equals(body.optionGroup().configNo())
                            || body.sourceVersion() != body.optionGroup().revision()) throw malformed();
                }
                if ("MODIFIER".equals(kind) && body.modifier() != null
                        && body.modifier().data() != null
                        && body.modifier().data().inventoryMode() != null) {
                    try {
                        ModifierInventoryMode.resolve(body.modifier().kind(),
                                body.modifier().data().salesSkuNo(), body.modifier().data().inventoryMode());
                    } catch (IllegalArgumentException invalid) {
                        throw malformed();
                    }
                }
                if (body.sourceVersion() < 0 || "MODIFIER".equals(kind)
                        && (body.modifier() == null || body.optionGroup() != null)
                        || "OPTION_GROUP".equals(kind) && (body.optionGroup() == null || body.modifier() != null)) {
                    throw malformed();
                }
                return version;
            }
            if ((version == 4 || version == 6) && "MENU".equals(kind)) {
                return MenuPublicationDocuments.validate(json, number, document);
            }
            if (version == 5 && "STATION".equals(kind)) {
                StationPublicationDocuments.validate(json, number, document);
                return 5;
            }
            if ((version == 3 || version == 6) && "RECIPE".equals(kind)) {
                RecipePublicationDocuments.validate(json, number, document);
                return version;
            }
            if ((version != 2 && version != 6) || !"BUNDLE".equals(kind)) throw malformed();
            exactQuantities(envelope);
            var body = json.readValue(document, BundlePublicationSnapshot.class);
            PublicationMarketPolicy.validateSchema(version, 2, body.marketContext());
            if (version == 6) PublicationMarketPolicy.validateSkuFacts(body.marketContext(), body.skuFacts());
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
            return version;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid frozen configuration document", failure);
        }
    }
    /** Kind dispatch, not a global schema number, selects the correct immutable original document type. */
    public static PublicationMarketContext marketContext(ObjectMapper json, String kind, String document) {
        Map<?, ?> envelope = json.readValue(document, Map.class);
        if (envelope == null || !Objects.equals(kind, envelope.get("configKind"))) throw malformed();
        return switch (kind) {
            case "MENU" -> json.readValue(document, MenuPublicationSnapshot.class).marketContext();
            case "MODIFIER", "OPTION_GROUP" -> json.readValue(document, PublicationSnapshot.class).marketContext();
            case "BUNDLE" -> json.readValue(document, BundlePublicationSnapshot.class).marketContext();
            case "RECIPE" -> json.readValue(document, RecipePublicationSnapshot.class).marketContext();
            default -> throw malformed();
        };
    }

    private static void validateModifierSkuFacts(PublicationSnapshot body) {
        var modifier = body.modifier();
        var data = modifier.data();
        if (!Set.of("ADDON", "COMBO").contains(modifier.kind())
                || "ADDON".equals(modifier.kind()) != data.components().isEmpty()) throw malformed();
        var actual = new HashSet<String>();
        body.skuFacts().forEach(fact -> actual.add(fact.skuNo()));
        String sales = data.salesSkuNo();
        if (sales != null && (!key(sales) || !actual.contains(sales))) throw malformed();
        if ("ADDON".equals(modifier.kind())
                && !actual.equals(sales == null ? Set.of() : Set.of(sales))) throw malformed();
    }

    private static void validateVersionNumbers(Object value) {
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> {
            if (Set.of("contentSchema", "schemaVersion", "currencyScale", "sourceVersion", "version", "revision",
                    "configVersion", "valueVersion", "dimVersion").contains(key)) {
                if (!(item instanceof Integer || item instanceof Long) || ((Number) item).longValue() < 0) {
                    throw malformed();
                }
            } else validateVersionNumbers(item);
        });
        else if (value instanceof List<?> list) list.forEach(PublicationDocuments::validateVersionNumbers);
    }

    private static void exactQuantities(Object value) {
        if (value instanceof Map<?,?> map) {
            map.forEach((key, item) -> {
                if (Set.of("minQty", "maxQty", "defaultQty", "unitsPerPick", "quantity").contains(key)) {
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
