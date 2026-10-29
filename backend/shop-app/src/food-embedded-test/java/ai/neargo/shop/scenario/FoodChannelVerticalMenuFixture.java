package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Reuses genuine native fixture operations without inheriting or executing another scenario's
 * tests.
 */
final class FoodChannelVerticalMenuFixture {
    record Menu(
            String number,
            String revision,
            String skuNo,
            String branchKind,
            List<String> materialSkus) {}

    final FoodNonPaymentNativeHttpIT original;
    final String token;

    FoodChannelVerticalMenuFixture(
            ApplicationContext context, JdbcTemplate sql, ObjectMapper json, int port) {
        original = new FoodNonPaymentNativeHttpIT();
        original.context = context;
        original.sql = sql;
        original.json = json;
        original.port = port;
        original.tokens = context.getBean(TokenStore.class);
        token = original.token();
    }

    Menu publish(FoodChannelVerticalProvisioning.ExternalStore external) throws Exception {
        return publish(external, "RECIPE_MATERIAL");
    }

    Menu publish(FoodChannelVerticalProvisioning.ExternalStore external, String branchKind)
            throws Exception {
        var f = original;
        var stores = f.context.getBean(ai.neargo.shop.merchant.service.MerchantStoreService.class);
        var profile = stores.profile("M0001", FoodNonPaymentNativeHttpIT.STORE);
        stores.save(
                "M0001",
                FoodNonPaymentNativeHttpIT.STORE,
                new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                        profile.announcement(),
                        profile.announcementUntil(),
                        "00:00-00:00",
                        profile.address(),
                        profile.addressDetail(),
                        profile.featured(),
                        profile.serviceScope(),
                        profile.serviceCommunityNos(),
                        profile.serviceCityCode(),
                        profile.fulfillmentReach(),
                        profile.serviceAreas() == null
                                ? List.of()
                                : profile.serviceAreas().stream()
                                        .map(
                                                area ->
                                                        new ai.neargo.shop.merchant.service
                                                                .MerchantStoreService.AreaCommand(
                                                                area.level(),
                                                                area.refCode(),
                                                                area.mode(),
                                                                area.geometry()))
                                        .toList(),
                        profile.latE6(),
                        profile.lngE6()));
        f.policy(token);
        f.pricingConfiguration();
        var gm = f.context.getBean(GoodsMapper.class);
        var sm = f.context.getBean(SkuMapper.class);
        // Extend the original native seeded product through its actual owner Mappers, as the
        // shipped native fixture does.
        var goods =
                f.json.readValue(
                        f.json.writeValueAsString(
                                gm.selectOne(new QueryWrapper<PrdGoods>().eq("goods_no", "G0001"))),
                        PrdGoods.class);
        var sku =
                f.json.readValue(
                        f.json.writeValueAsString(
                                sm.selectOne(
                                        new QueryWrapper<PrdSku>()
                                                .eq("sku_no", "SK0001")
                                                .eq("market", "CN"))),
                        PrdSku.class);
        String tag = f.key().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        goods.setId(null);
        goods.setGoodsNo("VCG" + tag);
        goods.setCover(null);
        goods.setStoreNo(FoodNonPaymentNativeHttpIT.STORE);
        goods.setTitle("native channel " + tag);
        goods.setSales(0);
        goods.setVersion(0L);
        goods.setFulfillments("[\"CHANNEL_DELIVERY\",\"STORE_PICKUP\"]");
        gm.insert(goods);
        sku.setId(null);
        sku.setSkuNo("VCS" + tag);
        sku.setGoodsNo(goods.getGoodsNo());
        sku.setVersion(0L);
        sku.setLockedStock(0);
        sm.insert(sku);
        f.context
                .getBean(GoodsPricingPolicyService.class)
                .save(
                        new GoodsPricingPolicyCommands.Context(
                                new GoodsPricingPolicyPort.Scope(
                                        "MAIN", "M0001", FoodNonPaymentNativeHttpIT.STORE),
                                "SF-M0001"),
                        goods.getGoodsNo(),
                        sku.getSkuNo(),
                        new GoodsPricingPolicyCommands.SaveReq(0, true, TaxClass.STANDARD),
                        f.key());
        f.ok(
                "PUT",
                "/biz/x/food/stores/"
                        + FoodNonPaymentNativeHttpIT.STORE
                        + "/dishes/"
                        + goods.getGoodsNo(),
                Map.of(
                        "dishKind",
                        "BUNDLE_MODIFIERS".equals(branchKind) ? "BUNDLE" : "SINGLE",
                        "prepSeconds",
                        0,
                        "presentation",
                        Map.of(),
                        "measurement",
                        Map.of("unit", "EA", "mode", "COUNT", "scale", 0, "allowedDeviation", 0)),
                token);
        if (!"BUNDLE_MODIFIERS".equals(branchKind)) f.prepareStock(sku.getSkuNo(), "EA");
        String recipe =
                "RECIPE_MATERIAL".equals(branchKind) ? f.recipe(token, sku.getSkuNo()) : null;
        var extras =
                recipe == null
                        ? extras(sku.getSkuNo(), "BUNDLE_MODIFIERS".equals(branchKind))
                        : null;
        var station =
                f.post(
                        "/biz/x/food/stores/" + FoodNonPaymentNativeHttpIT.STORE + "/stations",
                        Map.of("name", "native channel station", "stationType", "HOT"),
                        token,
                        f.key());
        String stationNo = station.path("configNo").asString();
        var pack =
                f.post(
                        "/biz/x/food/stores/" + FoodNonPaymentNativeHttpIT.STORE + "/stations",
                        Map.of("name", "native channel packing", "stationType", "PACK"),
                        token,
                        f.key());
        String packNo = pack.path("configNo").asString();
        var route =
                f.ok(
                        "GET",
                        "/biz/x/food/stores/"
                                + FoodNonPaymentNativeHttpIT.STORE
                                + "/sku-routes/"
                                + sku.getSkuNo(),
                        null,
                        token);
        f.ok(
                "PUT",
                "/biz/x/food/stores/"
                        + FoodNonPaymentNativeHttpIT.STORE
                        + "/sku-routes/"
                        + sku.getSkuNo(),
                Map.of(
                        "version",
                        route.path("version").asLong(),
                        "routes",
                        List.of(
                                Map.of("stationNo", stationNo, "taskRole", "MAKE"),
                                Map.of("stationNo", packNo, "taskRole", "PACK"))),
                token);
        if (extras != null)
            for (String child : extras.skus()) {
                var previous =
                        f.ok(
                                "GET",
                                "/biz/x/food/stores/"
                                        + FoodNonPaymentNativeHttpIT.STORE
                                        + "/sku-routes/"
                                        + child,
                                null,
                                token);
                f.ok(
                        "PUT",
                        "/biz/x/food/stores/"
                                + FoodNonPaymentNativeHttpIT.STORE
                                + "/sku-routes/"
                                + child,
                        Map.of(
                                "version",
                                previous.path("version").asLong(),
                                "routes",
                                List.of(
                                        Map.of("stationNo", stationNo, "taskRole", "MAKE"),
                                        Map.of("stationNo", packNo, "taskRole", "PACK"))),
                        token);
            }
        f.post(
                "/biz/x/food/stores/"
                        + FoodNonPaymentNativeHttpIT.STORE
                        + "/stations/"
                        + stationNo
                        + ":publish",
                Map.of("version", station.path("version").asLong(), "effectiveAt", effective()),
                token,
                f.key());
        f.post(
                "/biz/x/food/stores/"
                        + FoodNonPaymentNativeHttpIT.STORE
                        + "/stations/"
                        + packNo
                        + ":publish",
                Map.of("version", pack.path("version").asLong(), "effectiveAt", effective()),
                token,
                f.key());
        String menu =
                f.post(
                                "/biz/x/food/stores/" + FoodNonPaymentNativeHttpIT.STORE + "/menus",
                                Map.of(
                                        "name",
                                        "native channel menu",
                                        "menuType",
                                        "STANDARD",
                                        "timezone",
                                        "UTC",
                                        "priority",
                                        0),
                                token,
                                f.key())
                        .path("configNo")
                        .asString();
        String bindingNo = null;
        if (external != null) {
            var bindings = f.context.getBean(MenuChannelBindingService.class);
            var binding =
                    bindings.createBinding(
                            "M0001",
                            FoodNonPaymentNativeHttpIT.STORE,
                            "SF-M0001",
                            new CreateMenuChannelBindingReq(
                                    menu, "MOCK", external.accountRef(), external.storeRef()),
                            f.key());
            binding =
                    bindings.updateBinding(
                            "M0001",
                            FoodNonPaymentNativeHttpIT.STORE,
                            binding.bindingNo(),
                            "SF-M0001",
                            new UpdateMenuChannelBindingReq(
                                    binding.version(), "ACTIVE", external.storeRef()),
                            f.key());
            bindingNo = binding.bindingNo();
        }
        var composed =
                f.ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "COMPOSITION",
                                "version",
                                f.sql.queryForObject("SELECT version FROM fnb_menu WHERE menu_no=?", Long.class, menu),
                                "categories",
                                List.of(
                                        Map.of(
                                                "name",
                                                "channel",
                                                "sortNo",
                                                0,
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "goodsNo",
                                                                goods.getGoodsNo(),
                                                                "sortNo",
                                                                0,
                                                                "enabled",
                                                                true,
                                                                "skuScope",
                                                                "EXPLICIT",
                                                                "skuNos",
                                                                List.of(sku.getSkuNo()),
                                                                "optionMode",
                                                                extras == null
                                                                        ? "INHERIT"
                                                                        : "REPLACE",
                                                                "optionGroupNos",
                                                                extras == null
                                                                        ? List.of()
                                                                        : List.of(extras.groupNo()),
                                                                "skuRecipes",
                                                                recipe == null
                                                                        ? List.of()
                                                                        : List.of(
                                                                                Map.of(
                                                                                        "skuNo",
                                                                                        sku
                                                                                                .getSkuNo(),
                                                                                        "recipeNo",
                                                                                        recipe))))))),
                        token);
        var contexts =
                f.ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "CONTEXT",
                                "version",
                                composed.path("menu").path("version").asLong(),
                                "contexts",
                                List.of(
                                        external == null
                                                ? Map.of(
                                                        "orderSource",
                                                        "KIOSK",
                                                        "fulfillment",
                                                        "PICKUP")
                                                : Map.of(
                                                        "orderSource",
                                                        "CHANNEL",
                                                        "fulfillment",
                                                        "DELIVERY",
                                                        "channelBindingNo",
                                                        bindingNo,
                                                        "pricePolicyRef",
                                                        "MENU_PUBLISHED_PRICES_V1"))),
                        token);
        var schedule =
                f.ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "SCHEDULE",
                                "version",
                                contexts.path("menu").path("version").asLong(),
                                "weekSchedules",
                                IntStream.rangeClosed(1, 7)
                                        .mapToObj(
                                                day ->
                                                        Map.of(
                                                                "weekday",
                                                                day,
                                                                "startTime",
                                                                "00:00",
                                                                "endTime",
                                                                "00:00",
                                                                "endDayOffset",
                                                                1))
                                        .toList(),
                                "exceptions",
                                List.of()),
                        token);
        String revision =
                f.post(
                                "/biz/x/food/stores/"
                                        + FoodNonPaymentNativeHttpIT.STORE
                                        + "/menus/"
                                        + menu
                                        + ":publish",
                                Map.of(
                                        "version",
                                        schedule.path("menu").path("version").asLong(),
                                        "effectiveAt",
                                        effective()),
                                token,
                                f.key())
                        .path("publication")
                        .path("revisionNo")
                        .asString();
        return new Menu(
                menu,
                revision,
                sku.getSkuNo(),
                branchKind,
                extras == null ? List.of() : extras.skus());
    }

    private record Extras(String groupNo, List<String> skus) {}

    private Extras extras(String root, boolean bundle) throws Exception {
        var f = original;
        String store = FoodNonPaymentNativeHttpIT.STORE;
        String first = child(), second = child();
        String a = addon(first), b = addon(second);
        String compositeSku = child();
        var composite =
                f.post(
                        "/biz/x/food/stores/" + store + "/modifiers",
                        Map.of(
                                "name",
                                "native raw composite",
                                "kind",
                                "COMBO",
                                "salesSkuNo",
                                compositeSku,
                                "components",
                                List.of(
                                        Map.of("memberNo", a, "quantity", 1),
                                        Map.of("memberNo", b, "quantity", 2))),
                        token,
                        f.key());
        String combo = composite.path("configNo").asString();
        f.post(
                "/biz/x/food/modifiers/" + combo + ":publish",
                Map.of("version", composite.path("version").asLong(), "effectiveAt", effective()),
                token,
                f.key());
        var group =
                f.post(
                        "/biz/x/food/stores/" + store + "/option-groups",
                        Map.of("name", "native raw selection", "minQty", 1, "maxQty", 1),
                        token,
                        f.key());
        String groupNo = group.path("configNo").asString();
        var definition =
                f.ok(
                        "PUT",
                        "/biz/x/food/option-groups/" + groupNo + "/items",
                        Map.of(
                                "version",
                                group.path("version").asLong(),
                                "items",
                                List.of(
                                        Map.of(
                                                "optionItemNo",
                                                "VOC" + f.key().substring(0, 8),
                                                "sourceKind",
                                                "MODIFIER",
                                                "modifierNo",
                                                combo,
                                                "minQty",
                                                1,
                                                "maxQty",
                                                1,
                                                "defaultQty",
                                                1,
                                                "sortNo",
                                                0)),
                                "goodsNos",
                                List.of(f.sql.queryForObject("SELECT goods_no FROM prd_sku WHERE sku_no=? AND market='CN'", String.class, root)),
                                "selectionRules",
                                Map.of("mutexSets", List.of(), "requires", List.of())),
                        token);
        f.post(
                "/biz/x/food/stores/" + store + "/option-groups/" + groupNo + ":publish",
                Map.of("version", definition.path("version").asLong(), "effectiveAt", effective()),
                token,
                f.key());
        if (bundle) {
            var head =
                    f.post(
                            "/biz/x/food/stores/" + store + "/bundles",
                            Map.of("skuNo", root),
                            token,
                            f.key());
            String groupRef = "VBG" + f.key().substring(0, 8);
            var body =
                    f.ok(
                            "PUT",
                            "/biz/x/food/bundles/" + root + "/definition",
                            Map.of(
                                    "version",
                                    head.path("version").asLong(),
                                    "groups",
                                    List.of(
                                            Map.of(
                                                    "groupNo",
                                                    groupRef,
                                                    "name",
                                                    "native genuine sides",
                                                    "minQty",
                                                    2,
                                                    "maxQty",
                                                    2,
                                                    "sortNo",
                                                    0)),
                                    "choices",
                                    List.of(
                                            Map.of(
                                                    "choiceNo",
                                                    "VBC" + f.key().substring(0, 8),
                                                    "groupNo",
                                                    groupRef,
                                                    "skuNo",
                                                    first,
                                                    "unitsPerPick",
                                                    1,
                                                    "priceMode",
                                                    "INCLUDED",
                                                    "defaultQty",
                                                    1,
                                                    "minQty",
                                                    1,
                                                    "maxQty",
                                                    1,
                                                    "sortNo",
                                                    0),
                                            Map.of(
                                                    "choiceNo",
                                                    "VBC" + f.key().substring(0, 8),
                                                    "groupNo",
                                                    groupRef,
                                                    "skuNo",
                                                    second,
                                                    "unitsPerPick",
                                                    1,
                                                    "priceMode",
                                                    "ADD_SKU",
                                                    "defaultQty",
                                                    1,
                                                    "minQty",
                                                    1,
                                                    "maxQty",
                                                    1,
                                                    "sortNo",
                                                    1))),
                            token);
            f.post(
                    "/biz/x/food/stores/" + store + "/bundles/" + root + ":publish",
                    Map.of("version", body.path("version").asLong(), "effectiveAt", effective()),
                    token,
                    f.key());
        }
        return new Extras(groupNo, List.of(first, second, compositeSku));
    }

    private String addon(String sku) throws Exception {
        var f = original;
        var created =
                f.post(
                        "/biz/x/food/stores/" + FoodNonPaymentNativeHttpIT.STORE + "/modifiers",
                        Map.of(
                                "name",
                                "native real material member",
                                "kind",
                                "ADDON",
                                "salesSkuNo",
                                sku,
                                "components",
                                List.of()),
                        token,
                        f.key());
        String no = created.path("configNo").asString();
        f.post(
                "/biz/x/food/modifiers/" + no + ":publish",
                Map.of("version", created.path("version").asLong(), "effectiveAt", effective()),
                token,
                f.key());
        return no;
    }

    private String child() throws Exception {
        var f = original;
        String store = FoodNonPaymentNativeHttpIT.STORE;
        var gm = f.context.getBean(GoodsMapper.class);
        var sm = f.context.getBean(SkuMapper.class);
        var g =
                f.json.readValue(
                        f.json.writeValueAsString(
                                gm.selectOne(new QueryWrapper<PrdGoods>().eq("goods_no", "G0001"))),
                        PrdGoods.class);
        var s =
                f.json.readValue(
                        f.json.writeValueAsString(
                                sm.selectOne(
                                        new QueryWrapper<PrdSku>()
                                                .eq("sku_no", "SK0001")
                                                .eq("market", "CN"))),
                        PrdSku.class);
        String tag = f.key().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        g.setId(null);
        g.setGoodsNo("VMG" + tag);
        g.setCover(null);
        g.setStoreNo(store);
        g.setTitle("native channel material " + tag);
        g.setSales(0);
        g.setVersion(0L);
        g.setFulfillments("[\"CHANNEL_DELIVERY\"]");
        gm.insert(g);
        s.setId(null);
        s.setSkuNo("VMS" + tag);
        s.setGoodsNo(g.getGoodsNo());
        s.setVersion(0L);
        s.setLockedStock(0);
        sm.insert(s);
        f.context
                .getBean(GoodsPricingPolicyService.class)
                .save(
                        new GoodsPricingPolicyCommands.Context(
                                new GoodsPricingPolicyPort.Scope("MAIN", "M0001", store),
                                "SF-M0001"),
                        g.getGoodsNo(),
                        s.getSkuNo(),
                        new GoodsPricingPolicyCommands.SaveReq(0, true, TaxClass.STANDARD),
                        f.key());
        f.ok(
                "PUT",
                "/biz/x/food/stores/" + store + "/dishes/" + g.getGoodsNo(),
                Map.of(
                        "dishKind",
                        "SINGLE",
                        "prepSeconds",
                        0,
                        "presentation",
                        Map.of(),
                        "measurement",
                        Map.of("unit", "EA", "mode", "COUNT", "scale", 0, "allowedDeviation", 0)),
                token);
        f.prepareStock(s.getSkuNo(), "EA");
        return s.getSkuNo();
    }

    private static String effective() {
        return Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
