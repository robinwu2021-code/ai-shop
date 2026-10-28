package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One recipe document authority serves local and remote readers, including exact draft option
 * provenance.
 */
public final class RecipePublicationDocuments {
    private RecipePublicationDocuments() {}

    public static String optionIdentity(
            String entity, String group, String option, String kind, String spec, String modifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value :
                    List.of(
                            "recipe-option-identity:v1",
                            entity,
                            group,
                            option,
                            kind,
                            spec == null ? "" : spec,
                            modifier == null ? "" : modifier)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static RecipePublicationSnapshot validate(
            ObjectMapper json, String number, String document) {
        Map<?, ?> envelope = json.readValue(document, Map.class);
        if (Integer.valueOf(6).equals(envelope.get("contentSchema"))) json = PublicationJsonPolicy.strict(json);
        require(envelope.get("recipe") instanceof Map<?, ?>);
        Map<?, ?> recipeFields = (Map<?, ?>) envelope.get("recipe");
        require(
                recipeFields
                        .keySet()
                        .containsAll(Set.of("version", "configVersion", "lines", "adjustments")));
        exact(envelope);
        RecipePublicationSnapshot body =
                json.readerFor(RecipePublicationSnapshot.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(document);
        var r = body.recipe();
        require(
                (body.contentSchema() == 3 || body.contentSchema() == 6)
                        && "RECIPE".equals(body.configKind())
                        && number.equals(body.configNo())
                        && r != null
                        && number.equals(r.recipeNo())
                        && body.sourceVersion() == r.version()
                        && r.version() >= 0
                        && r.configVersion() >= 0
                        && key(r.storeNo(), 32)
                        && key(r.skuNo(), 32)
                        && r.recipeCode() != null
                        && r.recipeCode().matches("[A-Z][A-Z0-9_]{0,31}")
                        && key(r.yieldUnit(), 16)
                        && positive(r.yieldQty())
                        && Set.of("DRAFT", "ACTIVE").contains(r.status() == null ? "" : r.status())
                        && r.lines() != null
                        && !r.lines().isEmpty()
                        && r.lines().size() <= 100
                        && r.adjustments().size() <= 100
                        && body.skuFacts().size() == 1
                        && body.optionGroups().size() <= 100);
        PublicationMarketPolicy.validateSchema(body.contentSchema(), 3, body.marketContext());
        if (body.contentSchema() == 6) {
            PublicationMarketPolicy.validateSkuFacts(body.marketContext(), body.skuFacts());
            require(body.marketContext().storeNo().equals(r.storeNo()));
        }
        var sku = body.skuFacts().getFirst();
        require(
                sku != null
                        && r.skuNo().equals(sku.skuNo())
                        && r.storeNo().equals(sku.storeNo())
                        && key(sku.goodsNo(), 32)
                        && key(sku.entityNo(), 32)
                        && sku.version() >= 0);
        var materials = new HashMap<String, String>();
        for (var f : body.materialFacts()) {
            require(
                    MaterialCatalogFacts.validFact(f)
                            && sku.entityNo().equals(f.entityNo())
                            && materials.putIfAbsent(f.itemId(), f.definitionHash()) == null);
        }
        var expectedMaterials = new HashSet<String>();
        var lines = new HashMap<String, String>();
        for (var line : r.lines()) {
            require(
                    line != null
                            && key(line.lineNo(), 32)
                            && key(line.itemRef(), 32)
                            && positive(line.qty())
                            && nonnegative(line.lossRate())
                            && line.lossRate().compareTo(BigDecimal.ONE) < 0
                            && key(line.unit(), 16)
                            && lines.putIfAbsent(line.lineNo(), line.unit()) == null
                            && line.materialDefinitionHash() != null
                            && line.materialDefinitionHash().equals(materials.get(line.itemRef())));
            var fact =
                    body.materialFacts().stream()
                            .filter(f -> f.itemId().equals(line.itemRef()))
                            .findFirst();
            require(fact.isPresent() && line.unit().equals(fact.get().baseUom()));
            expectedMaterials.add(line.itemRef());
        }
        require(expectedMaterials.equals(materials.keySet()));
        var options = new HashMap<String, String>();
        var optionGroups = new HashMap<String, String>();
        var groupNos = new HashSet<String>();
        for (var group : body.optionGroups()) {
            require(
                    group != null
                            && sku.goodsNo().equals(group.goodsNo())
                            && group.publication() != null
                            && group.definition() != null);
            var dep = group.publication();
            var snapshot = group.definition();
            require(
                    "OPTION_GROUP".equals(dep.configKind())
                            && key(dep.configNo(), 32)
                            && key(dep.revisionNo(), 32)
                            && dep.revision() > 0
                            && groupNos.add(dep.configNo())
                            && snapshot.contentSchema() == (body.contentSchema() == 6 ? 6 : 1)
                            && "OPTION_GROUP".equals(snapshot.configKind())
                            && dep.configNo().equals(snapshot.configNo())
                            && snapshot.optionGroup() != null
                            && snapshot.modifier() == null
                            && dep.configNo().equals(snapshot.optionGroup().configNo())
                            && snapshot.sourceVersion() == snapshot.optionGroup().revision()
                            && PublicationDocuments.hash(
                                            json, PublicationDocuments.freeze(json, snapshot))
                                    .equals(dep.contentHash()));
            if (body.contentSchema() == 6) {
                PublicationMarketPolicy.requireSame(body.marketContext(), snapshot.marketContext());
                PublicationMarketPolicy.validateSkuFacts(snapshot.marketContext(), snapshot.skuFacts());
                require(PublicationDocuments.validate(json, "OPTION_GROUP", snapshot.configNo(),
                        PublicationDocuments.freeze(json, snapshot)) == 6);
            } else require(snapshot.marketContext() == null);
            for (OptionGroupConfigVO.Item item : snapshot.optionGroup().data().items()) {
                require(
                        key(item.optionItemNo(), 32)
                                && ("SPEC_VALUE".equals(item.sourceKind())
                                                && key(item.specValueNo(), 64)
                                                && item.modifierNo() == null
                                        || "MODIFIER".equals(item.sourceKind())
                                                && key(item.modifierNo(), 32)
                                                && item.specValueNo() == null));
                require(
                        options.putIfAbsent(
                                        item.optionItemNo(),
                                        optionIdentity(
                                                sku.entityNo(),
                                                dep.configNo(),
                                                item.optionItemNo(),
                                                item.sourceKind(),
                                                item.specValueNo(),
                                                item.modifierNo()))
                                == null);
                optionGroups.put(item.optionItemNo(), dep.configNo());
            }
        }
        var adjustmentIds = new HashSet<String>();
        var targets = new HashSet<List<String>>();
        var usedGroups = new HashSet<String>();
        for (var a : r.adjustments()) {
            require(
                    a != null
                            && key(a.adjustmentNo(), 32)
                            && adjustmentIds.add(a.adjustmentNo())
                            && key(a.optionItemNo(), 32)
                            && key(a.targetLineNo(), 32)
                            && targets.add(List.of(a.optionItemNo(), a.targetLineNo()))
                            && a.unit() != null
                            && a.unit().equals(lines.get(a.targetLineNo()))
                            && a.optionIdentityHash() != null
                            && a.optionIdentityHash().equals(options.get(a.optionItemNo()))
                            && Set.of("SET", "ADD", "REMOVE")
                                    .contains(a.action() == null ? "" : a.action())
                            && ("REMOVE".equals(a.action())
                                    ? a.qty() == null
                                    : nonnegative(a.qty())));
            usedGroups.add(optionGroups.get(a.optionItemNo()));
        }
        require(usedGroups.equals(groupNos));
        return body;
    }

    public static void scope(RecipePublicationSnapshot body, String entity, String store) {
        require(
                body.recipe().storeNo().equals(store)
                        && body.skuFacts().getFirst().entityNo().equals(entity));
    }

    private static void exact(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach(
                    (field, item) -> {
                        if (Set.of(
                                        "qty",
                                        "yieldQty",
                                        "lossRate",
                                        "minQty",
                                        "maxQty",
                                        "defaultQty",
                                        "quantity")
                                .contains(field)) {
                            if (item == null && "qty".equals(field)) return;
                            require(item instanceof String);
                            String text = (String) item;
                            require(text.length() <= 24 && text.matches("[0-9]+(?:\\.[0-9]+)?"));
                            var number = new BigDecimal(text);
                            require(
                                    number.scale() <= 6
                                            && number.compareTo(
                                                            new BigDecimal("999999999999.999999"))
                                                    <= 0);
                        } else if (Set.of("version", "revision", "configVersion", "sourceVersion",
                                        "contentSchema", "schemaVersion", "currencyScale")
                                .contains(field)) {
                            require(
                                    (item instanceof Integer || item instanceof Long)
                                            && ((Number) item).longValue() >= 0);
                        } else exact(item);
                    });
        } else if (value instanceof List<?> list) list.forEach(RecipePublicationDocuments::exact);
    }

    private static boolean positive(BigDecimal value) {
        return nonnegative(value) && value.signum() > 0;
    }

    private static boolean nonnegative(BigDecimal value) {
        return value != null
                && value.signum() >= 0
                && value.stripTrailingZeros().scale() <= 6
                && (long) value.precision() - value.scale() <= 12;
    }

    private static boolean key(String value, int max) {
        return MaterialCatalogFacts.validKey(value, max);
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Invalid frozen recipe document");
    }
}
