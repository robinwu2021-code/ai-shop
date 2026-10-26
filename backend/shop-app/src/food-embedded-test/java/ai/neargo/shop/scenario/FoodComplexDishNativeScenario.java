package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.support.FoodReadOnlyPayFixture;
import ai.neargo.shop.svc.ConfigServiceLocator;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.sql.DataSource;

/** Original owner fixtures plus actual HTTP; no business owner replacement or payment writes. */
final class FoodComplexDishNativeScenario {
    private static final String STORE = FoodNonPaymentNativeHttpIT.STORE;
    private final FoodNonPaymentNativeHttpIT f;
    private final String token;
    private final JdbcTemplate inv;
    private final InventoryAclService acl;

    record Product(String goods, String sku) {}

    FoodComplexDishNativeScenario(FoodNonPaymentNativeHttpIT fixture) {
        f = fixture;
        token = f.token();
        inv = new JdbcTemplate(f.context.getBean("invDataSource", DataSource.class));
        acl = f.context.getBean(InventoryAclService.class);
    }

    private JsonNode post(String path, Object body) throws Exception {
        return f.post(path, body, token, f.key());
    }

    private JsonNode put(String path, Object body) throws Exception {
        return f.ok("PUT", path, body, token);
    }

    private String effective() {
        return Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private String prefix() {
        return f.key().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    /** Explicitly extend original seeded product fixtures through their original Mappers. */
    private Product product(long price, String kind, String unit, boolean measured)
            throws Exception {
        String tag = prefix(), goodsNo = "NG" + tag, skuNo = "NS" + tag;
        var gm = f.context.getBean(GoodsMapper.class);
        var sm = f.context.getBean(SkuMapper.class);
        var g =
                f.json.readValue(
                        f.json.writeValueAsString(
                                gm.selectOne(new QueryWrapper<PrdGoods>().eq("goods_no", "G0001"))),
                        PrdGoods.class);
        g.setId(null);
        g.setGoodsNo(goodsNo);
        g.setStoreNo(STORE);
        g.setTitle("native " + tag);
        g.setSales(0);
        g.setVersion(0L);
        g.setFulfillments("[\"DINE_IN\",\"STORE_PICKUP\"]");
        gm.insert(g);
        var s =
                f.json.readValue(
                        f.json.writeValueAsString(
                                sm.selectOne(
                                        new QueryWrapper<PrdSku>()
                                                .eq("sku_no", "SK0001")
                                                .eq("market", "CN"))),
                        PrdSku.class);
        s.setId(null);
        s.setSkuNo(skuNo);
        s.setGoodsNo(goodsNo);
        s.setPrice(price);
        s.setOriginPrice(price);
        s.setStock(100);
        s.setLockedStock(0);
        s.setVersion(0L);
        sm.insert(s);
        put(
                "/biz/x/food/stores/" + STORE + "/dishes/" + goodsNo,
                Map.of(
                        "dishKind",
                        kind,
                        "prepSeconds",
                        0,
                        "presentation",
                        Map.of(),
                        "measurement",
                        Map.of(
                                "unit",
                                unit,
                                "mode",
                                measured ? "WEIGHT" : "COUNT",
                                "scale",
                                measured ? 3 : 0,
                                "allowedDeviation",
                                0)));
        f.context
                .getBean(GoodsPricingPolicyService.class)
                .save(
                        new GoodsPricingPolicyCommands.Context(
                                new GoodsPricingPolicyPort.Scope("MAIN", "M0001", STORE),
                                "SF-M0001"),
                        goodsNo,
                        skuNo,
                        new GoodsPricingPolicyCommands.SaveReq(0, true, TaxClass.STANDARD),
                        f.key());
        stock(skuNo, measured ? unit : "COUNT", measured);
        return new Product(goodsNo, skuNo);
    }

    private String stock(String sku, String unit, boolean measured) {
        String item = acl.upsertItem("M0001", sku, "native material", "fixture", null, null, unit);
        acl.locationIdOf("M0001", STORE);
        if (measured) {
            var mapper = f.context.getBean(ItemMapper.class);
            var row =
                    mapper.selectOne(
                            new QueryWrapper<ai.neargo.shop.inventory.entity.InvItem>()
                                    .eq("item_id", item));
            row.setWeighed(1);
            mapper.updateById(row);
        }
        f.context
                .getBean(StockPostingService.class)
                .post(
                        new StockPostingService.PostingDoc(
                                acl.ownerIdOf("M0001"),
                                "IN",
                                "CPLX-" + prefix(),
                                "PURCHASE",
                                LocalDateTime.now(ZoneOffset.UTC),
                                "NATIVE",
                                List.of(
                                        new StockPostingService.Line(
                                                1,
                                                item,
                                                acl.stockLocationOfExistingStore("M0001", STORE),
                                                20,
                                                100L,
                                                null,
                                                null,
                                                "CNY",
                                                null))));
        return item;
    }

    private void routes(List<String> skus) throws Exception {
        for (String sku : skus) {
            var station =
                    post(
                            "/biz/x/food/stores/" + STORE + "/stations",
                            Map.of("name", "complex " + prefix(), "stationType", "HOT"));
            var current =
                    f.ok("GET", "/biz/x/food/stores/" + STORE + "/sku-routes/" + sku, null, token);
            put(
                    "/biz/x/food/stores/" + STORE + "/sku-routes/" + sku,
                    Map.of(
                            "version",
                            current.path("version").asLong(),
                            "routes",
                            List.of(
                                    Map.of(
                                            "stationNo",
                                            station.path("configNo").asString(),
                                            "taskRole",
                                            "MAKE"))));
            post(
                    "/biz/x/food/stores/"
                            + STORE
                            + "/stations/"
                            + station.path("configNo").asString()
                            + ":publish",
                    Map.of(
                            "version",
                            station.path("version").asLong(),
                            "effectiveAt",
                            effective()));
        }
    }

    private Map<String, Object> menu(
            Product root, List<String> groups, List<Map<String, String>> recipes) throws Exception {
        String menu =
                post(
                                "/biz/x/food/stores/" + STORE + "/menus",
                                Map.of(
                                        "name",
                                        "complex " + prefix(),
                                        "menuType",
                                        "STANDARD",
                                        "timezone",
                                        "UTC",
                                        "priority",
                                        0))
                        .path("configNo")
                        .asString();
        var item =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "goodsNo",
                                root.goods(),
                                "sortNo",
                                0,
                                "enabled",
                                true,
                                "skuScope",
                                "EXPLICIT",
                                "skuNos",
                                List.of(root.sku()),
                                "optionMode",
                                "REPLACE",
                                "optionGroupNos",
                                groups,
                                "skuRecipes",
                                recipes.stream()
                                        .filter(r -> root.sku().equals(r.get("skuNo")))
                                        .toList()));
        var menuItems = new ArrayList<Map<String, Object>>();
        menuItems.add(item);
        for (var recipe : recipes) {
            if (root.sku().equals(recipe.get("skuNo"))) continue;
            String goods =
                    f.sql.queryForObject(
                            "SELECT goods_no FROM prd_sku WHERE sku_no=? AND market='CN'",
                            String.class,
                            recipe.get("skuNo"));
            menuItems.add(
                    Map.of(
                            "goodsNo",
                            goods,
                            "sortNo",
                            menuItems.size(),
                            "enabled",
                            true,
                            "skuScope",
                            "EXPLICIT",
                            "skuNos",
                            List.of(recipe.get("skuNo")),
                            "optionMode",
                            "REPLACE",
                            "optionGroupNos",
                            List.of(),
                            "skuRecipes",
                            List.of(recipe)));
        }
        var definition =
                put(
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "COMPOSITION",
                                "version",
                                0,
                                "categories",
                                List.of(
                                        Map.of(
                                                "name", "complex", "sortNo", 0, "items",
                                                menuItems))));
        var contexts =
                put(
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "CONTEXT",
                                "version",
                                definition.path("menu").path("version").asLong(),
                                "contexts",
                                List.of(Map.of("orderSource", "POS", "fulfillment", "DINE_IN"))));
        var schedules =
                put(
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "SCHEDULE",
                                "version",
                                contexts.path("menu").path("version").asLong(),
                                "weekSchedules",
                                IntStream.rangeClosed(1, 7)
                                        .mapToObj(
                                                d ->
                                                        Map.of(
                                                                "weekday",
                                                                d,
                                                                "startTime",
                                                                "00:00",
                                                                "endTime",
                                                                "00:00",
                                                                "endDayOffset",
                                                                1))
                                        .toList(),
                                "exceptions",
                                List.of()));
        String revision =
                post(
                                "/biz/x/food/stores/" + STORE + "/menus/" + menu + ":publish",
                                Map.of(
                                        "version",
                                        schedules.path("menu").path("version").asLong(),
                                        "effectiveAt",
                                        effective()))
                        .path("publication")
                        .path("revisionNo")
                        .asString();
        String itemNo =
                f.sql.queryForObject(
                        "SELECT i.menu_item_no FROM fnb_menu_item i JOIN fnb_menu_category c ON"
                                + " i.category_no=c.category_no WHERE c.menu_no=? AND i.goods_no=?",
                        String.class,
                        menu,
                        root.goods());
        return new LinkedHashMap<>(
                Map.of(
                        "sourceLineRef",
                        "COMPLEX",
                        "menuNo",
                        menu,
                        "menuItemNo",
                        itemNo,
                        "menuRevisionNo",
                        revision,
                        "goodsNo",
                        root.goods(),
                        "skuNo",
                        root.sku(),
                        "qty",
                        1,
                        "selections",
                        List.of(),
                        "bundleChoices",
                        List.of()));
    }

    private String modifier(Product p, String kind, List<Map<String, Object>> members)
            throws Exception {
        var created =
                post(
                        "/biz/x/food/stores/" + STORE + "/modifiers",
                        Map.of(
                                "name",
                                "native " + prefix(),
                                "kind",
                                kind,
                                "salesSkuNo",
                                p.sku(),
                                "components",
                                members));
        String no = created.path("configNo").asString();
        post(
                "/biz/x/food/modifiers/" + no + ":publish",
                Map.of("version", created.path("version").asLong(), "effectiveAt", effective()));
        return no;
    }

    void bundleAndCombo() throws Exception {
        f.policy(token);
        var root = product(1000, "BUNDLE", "EA", false);
        var sideA = product(300, "SINGLE", "EA", false);
        var sideB = product(400, "SINGLE", "EA", false);
        var extra = product(200, "ADDON", "EA", false);
        String a = modifier(sideA, "ADDON", List.of()), b = modifier(sideB, "ADDON", List.of());
        String combo =
                modifier(
                        extra,
                        "COMBO",
                        List.of(
                                Map.of("memberNo", a, "quantity", 1),
                                Map.of("memberNo", b, "quantity", 1)));
        var group =
                post(
                        "/biz/x/food/stores/" + STORE + "/option-groups",
                        Map.of("name", "combo " + prefix(), "minQty", 1, "maxQty", 1));
        String groupNo = group.path("configNo").asString();
        var groupDef =
                put(
                        "/biz/x/food/option-groups/" + groupNo + "/items",
                        Map.of(
                                "version",
                                group.path("version").asLong(),
                                "items",
                                List.of(
                                        Map.of(
                                                "optionItemNo",
                                                "OPT" + prefix(),
                                                "sourceKind",
                                                "MODIFIER",
                                                "modifierNo",
                                                combo,
                                                "minQty",
                                                0,
                                                "maxQty",
                                                1,
                                                "defaultQty",
                                                1,
                                                "sortNo",
                                                0)),
                                "goodsNos",
                                List.of(root.goods()),
                                "selectionRules",
                                Map.of("mutexSets", List.of(), "requires", List.of())));
        var groupPub =
                post(
                        "/biz/x/food/stores/" + STORE + "/option-groups/" + groupNo + ":publish",
                        Map.of(
                                "version",
                                groupDef.path("version").asLong(),
                                "effectiveAt",
                                effective()));
        var bundle = post("/biz/x/food/stores/" + STORE + "/bundles", Map.of("skuNo", root.sku()));
        String g = "BG" + prefix(), c1 = "BC" + prefix(), c2 = "BC" + prefix();
        var def =
                put(
                        "/biz/x/food/bundles/" + root.sku() + "/definition",
                        Map.of(
                                "version",
                                bundle.path("version").asLong(),
                                "groups",
                                List.of(
                                        Map.of(
                                                "groupNo",
                                                g,
                                                "name",
                                                "two sides",
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
                                                c1,
                                                "groupNo",
                                                g,
                                                "skuNo",
                                                sideA.sku(),
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
                                                c2,
                                                "groupNo",
                                                g,
                                                "skuNo",
                                                sideB.sku(),
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
                                                1))));
        post(
                "/biz/x/food/stores/" + STORE + "/bundles/" + root.sku() + ":publish",
                Map.of("version", def.path("version").asLong(), "effectiveAt", effective()));
        var recipes = new ArrayList<Map<String, String>>();
        for (var p : List.of(sideA, sideB, extra))
            recipes.add(Map.of("skuNo", p.sku(), "recipeNo", f.recipe(token, p.sku())));
        routes(List.of(root.sku(), sideA.sku(), sideB.sku(), extra.sku()));
        var line = menu(root, List.of(groupNo), recipes);
        line.put(
                "bundleChoices",
                List.of(Map.of("choiceNo", c1, "qty", 1), Map.of("choiceNo", c2, "qty", 1)));
        line.put(
                "selections",
                List.of(
                        Map.of(
                                "groupNo",
                                groupNo,
                                "sourceKind",
                                "MODIFIER",
                                "sourceNo",
                                combo,
                                "qty",
                                1,
                                "configVersion",
                                groupPub.path("publication").path("revision").asLong())));
        execute(
                line,
                1600,
                Map.of(
                        sideA.sku(),
                        BigDecimal.ONE,
                        sideB.sku(),
                        BigDecimal.ONE,
                        extra.sku(),
                        BigDecimal.ONE),
                acl.itemIdOf("M0001", "SK0003"),
                BigDecimal.ONE,
                4);
    }

    void measuredRecipe() throws Exception {
        f.policy(token);
        var root = product(10000, "SINGLE", "KG", true);
        var material = product(100, "SINGLE", "KG", true);
        String ingredient = acl.itemIdOf("M0001", material.sku());
        var draft =
                post(
                                "/biz/x/food/stores/" + STORE + "/recipes",
                                Map.of(
                                        "skuNo",
                                        root.sku(),
                                        "recipeCode",
                                        "W" + prefix(),
                                        "yieldQty",
                                        1,
                                        "yieldUnit",
                                        "KG"))
                        .path("recipe");
        String no = draft.path("recipeNo").asString();
        var changed =
                put(
                                "/biz/x/food/recipes/" + no + "/definition",
                                Map.of(
                                        "version",
                                        draft.path("version").asLong(),
                                        "yieldQty",
                                        1,
                                        "yieldUnit",
                                        "KG",
                                        "lines",
                                        List.of(
                                                Map.of(
                                                        "itemRef",
                                                        ingredient,
                                                        "qty",
                                                        2,
                                                        "unit",
                                                        "KG",
                                                        "lossRate",
                                                        0))))
                        .path("recipe");
        post(
                "/biz/x/food/recipes/" + no + ":publish",
                Map.of("version", changed.path("version").asLong(), "effectiveAt", effective()));
        routes(List.of(root.sku()));
        var line = menu(root, List.of(), List.of(Map.of("skuNo", root.sku(), "recipeNo", no)));
        line.put("qty", new BigDecimal("0.125"));
        execute(
                line,
                1250,
                Map.of(root.sku(), new BigDecimal("0.125")),
                ingredient,
                new BigDecimal("2"),
                1);
    }

    private JsonNode report(String date, String currency) throws Exception {
        var result =
                f.ok(
                        "GET",
                        "/biz/x/food/reports/business-day?businessDate="
                                + date
                                + "&currency="
                                + currency,
                        null,
                        token);
        assertThat(result.path("currency").asString()).isEqualTo(currency);
        assertThat(result.path("effectiveReceivableMinor").isIntegralNumber()).isTrue();
        assertThat(result.path("receivableAdjustmentMinor").isIntegralNumber()).isTrue();
        return result;
    }

    private JsonNode receivable(JsonNode report, String batch) {
        for (var row : report.path("originalOrderReceivables"))
            if (batch.equals(row.path("batchNo").asString())) return row;
        throw new AssertionError("Missing real CORE receivable " + batch);
    }

    private void execute(
            Map<String, Object> line,
            long price,
            Map<String, BigDecimal> taskQuantities,
            String ingredient,
            BigDecimal materialRate,
            int expectedItems)
            throws Exception {
        var table = f.table(token);
        f.sql.update(
                "UPDATE mch_store SET open_hours='00:00-00:00' WHERE entity_no='M0001' AND"
                        + " store_no=?",
                STORE);
        String check =
                post("/biz/x/food/stores/" + STORE + "/checks", f.openBody(table))
                        .path("check")
                        .path("checkNo")
                        .asString();
        var cart = f.cart(token, check);
        String cartNo = cart.path("cartNo").asString();
        var invalid = new LinkedHashMap<String, Object>(line);
        if (expectedItems > 1) {
            invalid.put("bundleChoices", List.of());
        } else {
            invalid.put("qty", new BigDecimal("0.1251"));
        }
        assertThat(
                        f.request(
                                        "PUT",
                                        "/biz/x/food/carts/" + cartNo + "/lines/COMPLEX",
                                        Map.of(
                                                "version",
                                                cart.path("version").asLong(),
                                                "line",
                                                invalid),
                                        token,
                                        f.key())
                                .path("code")
                                .asInt())
                .isEqualTo(10400);
        var edited =
                put(
                        "/biz/x/food/carts/" + cartNo + "/lines/COMPLEX",
                        Map.of("version", cart.path("version").asLong(), "line", line));
        try (var pay =
                new FoodReadOnlyPayFixture(
                        f.context.getBean(ConfigServiceLocator.class),
                        f.json,
                        "food-native-only")) {
            f.pricingConfiguration();
            f.awaitCapabilityWindow();
            var quote =
                    post(
                            "/biz/x/food/stores/" + STORE + "/quotes",
                            Map.of("cartNo", cartNo, "version", edited.path("version").asLong()));
            var login =
                    f.post(
                            "/mp/user/login",
                            Map.of(
                                    "grantType",
                                    "WECHAT_MP",
                                    "principal",
                                    "complex-" + prefix(),
                                    "agreed",
                                    true),
                            null,
                            f.key());
            String buyer =
                    f.ok("GET", "/mp/user/profile", null, login.path("token").asString())
                            .path("userNo")
                            .asString();
            var body =
                    Map.of(
                            "buyerNo",
                            buyer,
                            "cartNo",
                            cartNo,
                            "version",
                            edited.path("version").asLong(),
                            "quoteNo",
                            quote.path("quoteNo").asString(),
                            "confirmationDigest",
                            quote.path("confirmationDigest").asString(),
                            "payMode",
                            "POSTPAY");
            String placeKey = f.key();
            var batch = f.post("/biz/x/food/stores/" + STORE + "/batches", body, token, placeKey);
            assertThat(f.post("/biz/x/food/stores/" + STORE + "/batches", body, token, placeKey))
                    .isEqualTo(batch);
            assertThat(batch.path("status").asString()).isEqualTo("ACCEPTED");
            String batchNo = batch.path("batchNo").asString(),
                    order = batch.path("orderNo").asString();
            var items =
                    f.sql.queryForList(
                            "SELECT * FROM ord_item WHERE order_no=? ORDER BY id", order);
            assertThat(items).hasSize(expectedItems);
            if (expectedItems == 1) {
                assertThat(
                                f.sql.queryForObject(
                                        "SELECT sale_quantity FROM ord_item WHERE order_no=?",
                                        BigDecimal.class,
                                        order))
                        .isEqualByComparingTo(line.get("qty").toString());
                assertThat(
                                f.sql.queryForObject(
                                        "SELECT quantity_unit FROM ord_item WHERE order_no=?",
                                        String.class,
                                        order))
                        .isEqualTo("KG");
            } else {
                assertThat(
                                f.sql.queryForList(
                                        "SELECT amount FROM ord_item WHERE order_no=? ORDER BY"
                                                + " amount",
                                        Long.class,
                                        order))
                        .containsExactly(0L, 200L, 400L, 1000L);
            }
            assertThat(
                            f.sql.queryForObject(
                                    "SELECT pay_amount FROM ord_order WHERE order_no=?",
                                    Long.class,
                                    order))
                    .isEqualTo(price);
            String date =
                    f.sql.queryForObject(
                                    "SELECT business_date FROM fnb_order_batch WHERE batch_no=?",
                                    java.time.LocalDate.class,
                                    batchNo)
                            .toString();
            var before = report(date, "CNY");
            assertThat(receivable(before, batchNo).path("effectiveMinor").asLong())
                    .isEqualTo(price);
            assertThat(report(date, "AED").path("effectiveReceivableMinor").asLong()).isZero();
            String accepted =
                    f.sql.queryForObject(
                            "SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND"
                                    + " event_type='DINING_ORDER_ACCEPTED'",
                            String.class,
                            batchNo);
            f.deliver(accepted, FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER);
            var tasks =
                    f.sql.queryForList(
                            "SELECT t.task_no,t.station_no,t.qty,COALESCE(c.sku_no,l.sku_no) AS"
                                + " sku_no FROM fnb_kitchen_task t JOIN fnb_kitchen_ticket k ON"
                                + " k.ticket_no=t.ticket_no JOIN fnb_order_line l ON"
                                + " l.food_line_no=t.food_line_no LEFT JOIN fnb_order_component c"
                                + " ON c.component_no=t.component_no WHERE k.batch_no=? AND"
                                + " k.kind='MAKE' ORDER BY t.task_no",
                            batchNo);
            assertThat(tasks).hasSize(taskQuantities.size());
            assertThat(tasks.stream().map(task -> task.get("sku_no").toString()).toList())
                    .containsExactlyInAnyOrderElementsOf(taskQuantities.keySet());
            String owner = acl.ownerIdOf("M0001"),
                    location = acl.stockLocationOfExistingStore("M0001", STORE);
            String materialScope = " WHERE owner_id=? AND location_id=? AND item_id=?";
            var materialBefore =
                    inv.queryForObject(
                            "SELECT SUM(on_hand) FROM inv_stock_balance" + materialScope,
                            BigDecimal.class,
                            owner,
                            location,
                            ingredient);
            var ledgerBefore =
                    inv.queryForList(
                            "SELECT * FROM inv_ledger" + materialScope + " ORDER BY id",
                            owner,
                            location,
                            ingredient);
            for (var task : tasks) {
                String sku = task.get("sku_no").toString(), taskNo = task.get("task_no").toString();
                BigDecimal qty = taskQuantities.get(sku);
                assertThat(qty).isNotNull();
                assertThat((BigDecimal) task.get("qty")).isEqualByComparingTo(qty);
                assertThat(
                                f.sql.queryForObject(
                                        "SELECT COUNT(*) FROM fnb_sku_station WHERE"
                                            + " entity_no='M0001' AND sku_no=? AND station_no=?",
                                        Integer.class,
                                        sku,
                                        task.get("station_no")))
                        .isEqualTo(1);
                var start =
                        post(
                                "/biz/x/food/tasks/" + taskNo + ":start",
                                Map.of(
                                        "version",
                                        f.taskVersion(taskNo),
                                        "qty",
                                        qty,
                                        "reason",
                                        "complex start"));
                String event = start.path("eventNo").asString();
                String consumeKey = f.key(),
                        path = "/biz/x/food/production-events/" + event + ":consume";
                var balanceBeforeConsume =
                        inv.queryForList(
                                "SELECT * FROM inv_stock_balance" + materialScope,
                                owner,
                                location,
                                ingredient);
                int countBeforeConsume =
                        inv.queryForObject(
                                "SELECT COUNT(*) FROM inv_ledger" + materialScope,
                                Integer.class,
                                owner,
                                location,
                                ingredient);
                var onHandBeforeConsume =
                        inv.queryForObject(
                                "SELECT SUM(on_hand) FROM inv_stock_balance" + materialScope,
                                BigDecimal.class,
                                owner,
                                location,
                                ingredient);
                var receipt = f.post(path, Map.of(), token, consumeKey);
                assertThat(receipt.path("status").asString()).isEqualTo("CONFIRMED");
                var replayLedger =
                        inv.queryForList(
                                "SELECT * FROM inv_ledger" + materialScope + " ORDER BY id",
                                owner,
                                location,
                                ingredient);
                var replayBalance =
                        inv.queryForList(
                                "SELECT * FROM inv_stock_balance" + materialScope,
                                owner,
                                location,
                                ingredient);
                assertThat(replayLedger).hasSize(countBeforeConsume + 1);
                assertThat(
                                inv.queryForObject(
                                        "SELECT SUM(on_hand) FROM inv_stock_balance"
                                                + materialScope,
                                        BigDecimal.class,
                                        owner,
                                        location,
                                        ingredient))
                        .isEqualByComparingTo(
                                onHandBeforeConsume.subtract(qty.multiply(materialRate)));
                assertThat(replayBalance).isNotEqualTo(balanceBeforeConsume);
                assertThat(f.post(path, Map.of(), token, consumeKey)).isEqualTo(receipt);
                assertThat(
                                inv.queryForList(
                                        "SELECT * FROM inv_ledger" + materialScope + " ORDER BY id",
                                        owner,
                                        location,
                                        ingredient))
                        .isEqualTo(replayLedger);
                assertThat(
                                inv.queryForList(
                                        "SELECT * FROM inv_stock_balance" + materialScope,
                                        owner,
                                        location,
                                        ingredient))
                        .isEqualTo(replayBalance);
                var proof =
                        f.json.readTree(
                                inv.queryForObject(
                                        "SELECT receipt_snapshot FROM inv_production_operation"
                                                + " WHERE owner_id=? AND store_no=? AND event_no=?",
                                        String.class,
                                        owner,
                                        STORE,
                                        event));
                assertThat(proof.path("issuedLines")).hasSize(1);
                var issued = proof.path("issuedLines").get(0);
                var ledger =
                        inv.queryForMap(
                                "SELECT * FROM inv_ledger WHERE id=?",
                                Long.parseLong(issued.path("ledgerRef").asString()));
                assertThat(ledger.get("item_id")).isEqualTo(ingredient);
                assertThat(ledger.get("owner_id")).isEqualTo(owner);
                assertThat(ledger.get("location_id")).isEqualTo(location);
                assertThat(ledger.get("doc_no")).isEqualTo(issued.path("documentNo").asString());
                assertThat((BigDecimal) ledger.get("qty_delta"))
                        .isEqualByComparingTo(qty.multiply(materialRate).negate());
                post(
                        "/biz/x/food/tasks/" + taskNo + ":ready",
                        Map.of(
                                "version",
                                f.taskVersion(taskNo),
                                "qty",
                                qty,
                                "reason",
                                "complex ready"));
            }
            var expectedConsumed =
                    taskQuantities.values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .multiply(materialRate);
            assertThat(
                            inv.queryForObject(
                                    "SELECT SUM(on_hand) FROM inv_stock_balance" + materialScope,
                                    BigDecimal.class,
                                    owner,
                                    location,
                                    ingredient))
                    .isEqualByComparingTo(materialBefore.subtract(expectedConsumed));
            assertThat(
                            inv.queryForList(
                                    "SELECT * FROM inv_ledger" + materialScope + " ORDER BY id",
                                    owner,
                                    location,
                                    ingredient))
                    .hasSize(ledgerBefore.size() + tasks.size());
            var ledgerSnapshot =
                    inv.queryForList(
                            "SELECT * FROM inv_ledger WHERE owner_id=? AND location_id=? ORDER BY"
                                    + " id",
                            owner,
                            location);
            var balanceSnapshot =
                    inv.queryForList(
                            "SELECT item_id,on_hand FROM inv_stock_balance WHERE owner_id=? AND"
                                    + " location_id=? ORDER BY item_id",
                            owner,
                            location);
            String foodLine =
                    f.sql.queryForObject(
                            "SELECT food_line_no FROM fnb_order_line WHERE batch_no=?",
                            String.class,
                            batchNo);
            var quantities = List.of(Map.of("foodLineNo", foodLine, "qty", line.get("qty")));
            var handover =
                    post(
                            "/biz/x/food/batches/" + batchNo + ":handover",
                            Map.of(
                                    "kind",
                                    "DINE_IN",
                                    "lines",
                                    quantities,
                                    "reason",
                                    "complex handover"));
            assertThat(handover.path("deliveryPending").asBoolean()).isFalse();
            assertThat(handover.path("coreDeliveryRef").asString()).isNotBlank();
            var returned =
                    post(
                            "/biz/x/food/batches/" + batchNo + "/business-returns",
                            Map.of(
                                    "batchVersion",
                                    f.sql.queryForObject(
                                            "SELECT version FROM fnb_order_batch WHERE batch_no=?",
                                            Long.class,
                                            batchNo),
                                    "lines",
                                    quantities,
                                    "reason",
                                    "complex return"));
            String returnNo = returned.path("returnNo").asString(),
                    approvalKey = f.key(),
                    approvalPath = "/biz/x/food/business-returns/" + returnNo + ":approve";
            var approval =
                    Map.of(
                            "version",
                            returned.path("version").asLong(),
                            "reason",
                            "complex approval");
            assertThat(
                            f.post(approvalPath, approval, token, approvalKey)
                                    .path("pending")
                                    .asBoolean())
                    .isTrue();
            var kitchen = f.ok("GET", "/biz/x/food/returns/" + returnNo + "/kitchen", null, token);
            var decisions = new ArrayList<Map<String, String>>();
            for (var i : kitchen.path("items"))
                decisions.add(
                        Map.of(
                                "returnItemNo",
                                i.path("returnItemNo").asString(),
                                "disposition",
                                "KEEP"));
            // Kitchen dispositions follow returned parent lines, not the number of make tasks.
            assertThat(decisions).hasSize(quantities.size());
            post(
                    "/biz/x/food/returns/" + returnNo + "/kitchen",
                    Map.of(
                            "version",
                            kitchen.path("version").asLong(),
                            "decisions",
                            decisions,
                            "reason",
                            "keep consumed materials"));
            var confirmed = f.post(approvalPath, approval, token, approvalKey);
            assertThat(confirmed.path("pending").asBoolean()).isFalse();
            assertThat(f.post(approvalPath, approval, token, approvalKey)).isEqualTo(confirmed);
            assertThat(
                            f.sql.queryForList(
                                    "SELECT * FROM ord_item WHERE order_no=? ORDER BY id", order))
                    .isEqualTo(items);
            assertThat(
                            f.sql.queryForObject(
                                    "SELECT SUM(amount_minor) FROM"
                                        + " ord_industry_receivable_adjustment WHERE order_no=?",
                                    Long.class,
                                    order))
                    .isEqualTo(-price);
            var after = report(date, "CNY");
            assertThat(receivable(after, batchNo).path("effectiveMinor").asLong()).isZero();
            assertThat(receivable(after, batchNo).path("adjustmentMinor").asLong())
                    .isEqualTo(-price);
            assertThat(
                            before.path("effectiveReceivableMinor").asLong()
                                    - after.path("effectiveReceivableMinor").asLong())
                    .isEqualTo(price);
            assertThat(report(date, "AED").path("effectiveReceivableMinor").asLong()).isZero();
            assertThat(
                            inv.queryForList(
                                    "SELECT * FROM inv_ledger WHERE owner_id=? AND location_id=?"
                                            + " ORDER BY id",
                                    owner,
                                    location))
                    .isEqualTo(ledgerSnapshot);
            assertThat(
                            inv.queryForList(
                                    "SELECT item_id,on_hand FROM inv_stock_balance WHERE owner_id=?"
                                            + " AND location_id=? ORDER BY item_id",
                                    owner,
                                    location))
                    .isEqualTo(balanceSnapshot);
            assertThat(
                            f.sql.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    order))
                    .isEqualTo("WAIT_PAY");
            assertThat(
                            f.sql.queryForObject(
                                    "SELECT paid_at FROM ord_order WHERE order_no=?",
                                    Object.class,
                                    order))
                    .isNull();
        }
    }
}
