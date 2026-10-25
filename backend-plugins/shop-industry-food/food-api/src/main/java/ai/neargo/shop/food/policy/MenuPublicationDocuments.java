package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Self-contained historical validation reads embedded facts only, never current mutable menu or
 * dependency heads.
 */
public final class MenuPublicationDocuments {
    private MenuPublicationDocuments() {}

    public static String freeze(ObjectMapper json, MenuPublicationSnapshot snapshot) {
        Object tree =
                json.readerFor(Object.class)
                        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readValue(json.writeValueAsString(snapshot));
        return json.writeValueAsString(exact(tree));
    }

    private static Object exact(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach(
                    (key, item) ->
                            sorted.put(
                                    (String) key,
                                    "allowedDeviation".equals(key) && item instanceof Number n
                                            ? new BigDecimal(n.toString())
                                                    .stripTrailingZeros()
                                                    .toPlainString()
                                            : exact(item)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(MenuPublicationDocuments::exact).toList();
        return value;
    }

    public static int validate(ObjectMapper json, String number, String document) {
        try {
            validateIntegers(json.readValue(document, Map.class));
            var body = json.readValue(document, MenuPublicationSnapshot.class);
            require(
                    body.contentSchema() == 4
                            && "MENU".equals(body.configKind())
                            && number.equals(body.configNo()));
            var c = body.menu();
            validateCaptured(c);
            for (var category : c.definition().composition().categories())
                for (var item : category.items()) {
                    var prices = MenuPricePolicy.normalize(item.skuPrices());
                    if (prices != null)
                        for (var price : prices)
                            require(
                                    body.skuFacts().stream()
                                            .anyMatch(fact -> price.skuNo().equals(fact.skuNo())));
                }

            require(
                    number.equals(c.definition().config().menuNo())
                            && body.sourceVersion() == c.definition().config().version());
            var goods = new HashMap<String, GoodsFact>();
            var owners = new HashMap<String, String>();
            for (var fact : body.goodsFacts()) {
                require(
                        fact != null
                                && key(fact.goodsNo())
                                && c.entityNo().equals(fact.entityNo())
                                && c.storeNo().equals(fact.storeNo())
                                && fact.version() >= 0
                                && fact.skus() != null
                                && !fact.skus().isEmpty()
                                && fact.skus().size() <= 100
                                && goods.put(fact.goodsNo(), fact) == null);
                for (var sku : fact.skus())
                    require(
                            sku != null
                                    && key(sku.skuNo())
                                    && sku.version() >= 0
                                    && owners.put(sku.skuNo(), fact.goodsNo()) == null);
            }
            require(goods.keySet().equals(goodsNos(c)));
            var expected = new TreeSet<String>();
            var recipeSkus = new HashMap<String, String>();
            for (var item : items(c)) {
                var fact = goods.get(item.goodsNo());
                if ("ALL".equals(item.skuScope()))
                    fact.skus().forEach(s -> expected.add(s.skuNo()));
                else
                    for (String sku : item.skuNos()) {
                        require(item.goodsNo().equals(owners.get(sku)));
                        expected.add(sku);
                    }
                item.skuRecipes().forEach(r -> recipeSkus.put(r.recipeNo(), r.skuNo()));
            }
            expected.addAll(dependencySkus(json, c));
            var actual = new HashMap<String, SkuIdentityFact>();
            for (var fact : body.skuFacts()) {
                require(
                        fact != null
                                && key(fact.skuNo())
                                && key(fact.goodsNo())
                                && fact.version() >= 0
                                && c.entityNo().equals(fact.entityNo())
                                && c.storeNo().equals(fact.storeNo())
                                && actual.put(fact.skuNo(), fact) == null);
                if (owners.containsKey(fact.skuNo())) {
                    require(owners.get(fact.skuNo()).equals(fact.goodsNo()));
                    var source =
                            goods.get(fact.goodsNo()).skus().stream()
                                    .filter(s -> s.skuNo().equals(fact.skuNo()))
                                    .findFirst()
                                    .orElseThrow();
                    require(source.version() == fact.version());
                }
            }
            require(actual.keySet().equals(expected));
            var dependencies = new HashMap<String, Dependency>();
            for (var dep : c.dependencies()) {
                require(
                        dep != null
                                && key(dep.revisionNo())
                                && key(dep.configNo())
                                && dep.revision() > 0
                                && Set.of("OPTION_GROUP", "MODIFIER", "BUNDLE", "RECIPE")
                                        .contains(dep.configKind())
                                && dep.contentHash() != null
                                && dep.contentHash().matches("[0-9a-f]{64}")
                                && dependencies.put(dep.configKind() + ":" + dep.configNo(), dep)
                                        == null);
                require(
                        PublicationDocuments.validate(
                                        json,
                                        dep.configKind(),
                                        dep.configNo(),
                                        dep.definitionSnapshot())
                                == dep.contentSchema());
                require(
                        PublicationDocuments.hash(json, dep.definitionSnapshot())
                                .equals(dep.contentHash()));
                validateDependencyScope(json, c, dep);
                if ("RECIPE".equals(dep.configKind()) && recipeSkus.containsKey(dep.configNo())) {
                    var recipe =
                            json.readValue(
                                            dep.definitionSnapshot(),
                                            RecipePublicationSnapshot.class)
                                    .recipe();
                    require(
                            recipeSkus.get(dep.configNo()).equals(recipe.skuNo())
                                    && c.storeNo().equals(recipe.storeNo()));
                }
            }
            for (var options : c.itemOptions())
                for (String group : options.optionGroupNos())
                    require(dependencies.containsKey("OPTION_GROUP:" + group));
            for (String recipe : recipeSkus.keySet())
                require(dependencies.containsKey("RECIPE:" + recipe));
            for (var dish : c.dishes())
                if ("BUNDLE".equals(dish.dishKind())) {
                    for (var item : items(c))
                        if (item.goodsNo().equals(dish.goodsNo())) {
                            var numbers =
                                    "ALL".equals(item.skuScope())
                                            ? goods.get(dish.goodsNo()).skus().stream()
                                                    .map(s -> s.skuNo())
                                                    .toList()
                                            : item.skuNos();
                            for (String sku : numbers)
                                require(dependencies.containsKey("BUNDLE:" + sku));
                        }
                }
            for (var dep : c.dependencies())
                if (dep.contentSchema() == 1) {
                    var frozen =
                            json.readValue(dep.definitionSnapshot(), PublicationSnapshot.class);
                    for (var ref : frozen.dependencies()) {
                        var target = dependencies.get(ref.configKind() + ":" + ref.configNo());
                        require(
                                target != null
                                        && target.revisionNo().equals(ref.revisionNo())
                                        && target.revision() == ref.revision()
                                        && target.contentHash().equals(ref.contentHash()));
                    }
                }
            return 4;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid frozen menu document", failure);
        }
    }

    public static void validateCaptured(Captured c) {
        require(
                c != null
                        && key(c.entityNo())
                        && key(c.storeNo())
                        && c.definition() != null
                        && MenuDefinitionPolicy.valid(c.definition().config(), c.storeNo())
                        && !"DISABLED".equals(c.definition().config().status())
                        && c.definition().schedule() != null
                        && c.definition().schedule().weekSchedules() != null
                        && c.definition().schedule().exceptions() != null
                        && c.definition().contexts() != null
                        && !c.definition().contexts().isEmpty());
        MenuCompositionDefinitions.normalize(c.definition().composition());
        MenuContextDefinitions.normalize(c.definition().contexts());
        MenuScheduleDefinitions.normalize(c.definition().schedule());
        require(
                !items(c).isEmpty()
                        && c.dishes().size() <= 300
                        && c.itemOptions().size() <= 300
                        && c.dependencies().size() <= 1000
                        && c.channelBindings().size() <= 50);
        var expectedGoods = goodsNos(c);
        var actualGoods = new HashSet<String>();
        for (var dish : c.dishes())
            require(
                    dish != null
                            && actualGoods.add(dish.goodsNo())
                            && dish.version() >= 0
                            && dish.configVersion() >= 0
                            && dish.prepSeconds() >= 0
                            && Set.of("SINGLE", "BUNDLE", "ADDON").contains(dish.dishKind())
                            && dish.presentation() != null
                            && dish.measurement() != null
                            && DiningMeasurementPolicy.valid(dish.measurement()));
        require(actualGoods.equals(expectedGoods));
        var expectedItems = new HashSet<String>();
        var optionItems = new HashSet<String>();
        for (var category : c.definition().composition().categories()) {
            require(key(category.categoryNo()));
            for (var item : category.items()) require(key(item.menuItemNo()));
        }
        for (var item : items(c)) expectedItems.add(item.menuItemNo());
        for (var options : c.itemOptions()) {
            require(
                    options != null
                            && optionItems.add(options.menuItemNo())
                            && options.optionGroupNos().size() <= 50
                            && options.optionGroupNos().stream()
                                    .allMatch(MenuPublicationDocuments::key)
                            && new HashSet<>(options.optionGroupNos()).size()
                                    == options.optionGroupNos().size());
            var item =
                    items(c).stream()
                            .filter(i -> i.menuItemNo().equals(options.menuItemNo()))
                            .findFirst()
                            .orElseThrow();
            if ("REPLACE".equals(item.optionMode()))
                require(item.optionGroupNos().equals(options.optionGroupNos()));
        }
        require(optionItems.equals(expectedItems));
        var bindingNos = new HashSet<String>();
        for (var b : c.channelBindings())
            require(
                    b != null
                            && key(b.bindingNo())
                            && bindingNos.add(b.bindingNo())
                            && c.storeNo().equals(b.storeNo())
                            && c.definition().config().menuNo().equals(b.menuNo())
                            && "ACTIVE".equals(b.status())
                            && b.version() != null
                            && b.version() >= 0
                            && MaterialCatalogFacts.validKey(b.channelAccountRef(), 64)
                            && MaterialCatalogFacts.validKey(b.externalStoreRef(), 64));
        var expectedBindings = new HashSet<String>();
        c.definition()
                .contexts()
                .forEach(
                        ctx -> {
                            if (ctx.channelBindingNo() != null)
                                expectedBindings.add(ctx.channelBindingNo());
                        });
        require(bindingNos.equals(expectedBindings));
    }

    private static void validateDependencyScope(ObjectMapper json, Captured c, Dependency dep) {
        List<SkuIdentityFact> facts;
        if (dep.contentSchema() == 1)
            facts = json.readValue(dep.definitionSnapshot(), PublicationSnapshot.class).skuFacts();
        else if (dep.contentSchema() == 2)
            facts =
                    json.readValue(dep.definitionSnapshot(), BundlePublicationSnapshot.class)
                            .skuFacts();
        else
            facts =
                    json.readValue(dep.definitionSnapshot(), RecipePublicationSnapshot.class)
                            .skuFacts();
        for (var fact : facts)
            require(c.entityNo().equals(fact.entityNo()) && c.storeNo().equals(fact.storeNo()));
    }

    public static List<MenuItemInput> items(Captured c) {
        return c.definition().composition().categories().stream()
                .flatMap(category -> category.items().stream())
                .filter(item -> Boolean.TRUE.equals(item.enabled()))
                .toList();
    }

    public static Set<String> goodsNos(Captured c) {
        var result = new TreeSet<String>();
        items(c).forEach(i -> result.add(i.goodsNo()));
        return result;
    }

    public static Set<String> dependencySkus(ObjectMapper json, Captured c) {
        var result = new TreeSet<String>();
        for (var dep : c.dependencies()) {
            List<SkuIdentityFact> facts;
            if (dep.contentSchema() == 1)
                facts =
                        json.readValue(dep.definitionSnapshot(), PublicationSnapshot.class)
                                .skuFacts();
            else if (dep.contentSchema() == 2)
                facts =
                        json.readValue(dep.definitionSnapshot(), BundlePublicationSnapshot.class)
                                .skuFacts();
            else if (dep.contentSchema() == 3)
                facts =
                        json.readValue(dep.definitionSnapshot(), RecipePublicationSnapshot.class)
                                .skuFacts();
            else throw new IllegalStateException("Invalid menu dependency schema");
            facts.forEach(f -> result.add(f.skuNo()));
        }
        return result;
    }

    private static void validateIntegers(Object value) {
        if (value instanceof Map<?, ?> map)
            map.forEach(
                    (key, item) -> {
                        if (Set.of(
                                        "contentSchema",
                                        "sourceVersion",
                                        "version",
                                        "configVersion",
                                        "revision",
                                        "routeVersion",
                                        "prepSeconds",
                                        "priority",
                                        "sortNo",
                                        "scale",
                                        "weekday",
                                        "endDayOffset")
                                .contains(key)) {
                            if (item != null)
                                require(item instanceof Integer || item instanceof Long);
                        } else validateIntegers(item);
                    });
        else if (value instanceof List<?> list)
            list.forEach(MenuPublicationDocuments::validateIntegers);
    }

    private static boolean key(String value) {
        return MaterialCatalogFacts.validKey(value, 32);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Malformed frozen menu");
    }
}
