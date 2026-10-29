package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingConfigCompiler;
import ai.neargo.shop.common.pricing.SystemConstraints;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.event.outbox.DeliveryRef;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.RetryPolicy;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.inventory.port.InventoryProductionPortImpl;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands;
import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.support.FoodReadOnlyPayFixture;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.trade.dto.PricingConfigCommands;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingRuntimeConfigService;
import ai.neargo.shop.trade.service.PricingTaxSettingService;
import ai.neargo.shop.trade.service.PricingTemplateService;
import ai.neargo.shop.trade.service.StorePricingConfigService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.aopalliance.intercept.MethodInterceptor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;

import javax.sql.DataSource;

/**
 * Real loopback HTTP, original security/context, Service, MyBatis and disposable native databases.
 * Tokens are issued by the original store to seeded fixture members; no business owner is mocked.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(
        properties = {
            "shop.seed.enabled=true",
            "shop.food.menu-publication-enabled=true",
            "shop.food.bundle-publication-enabled=true",
            "shop.food.recipe-publication-enabled=true",
            "shop.food.station-publication-enabled=true",
            "shop.inventory.stock-authority=INVENTORY",
            "shop.services.internal-token=food-native-only",
            "shop.outbox.sources.platform.mode=UNIFIED",
            "shop.outbox.sources.inventory.mode=UNIFIED"
        })
class FoodNonPaymentNativeHttpIT extends FoodEmbeddedStartupIT {
    @LocalServerPort int port;
    @Autowired TokenStore tokens;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String STORE = "ST-M0001";

    String token() {
        return tokens.issue(
                TokenStore.SessionData.of(LoginUser.merchant("SF-M0001", "native fixture owner")));
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    JsonNode request(String method, String path, Object body, String token, String key)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(45))
                        .header("X-Store-No", STORE);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s %s: %s", method, path, response.body())
                .isEqualTo(200);
        return json.readTree(response.body());
    }

    JsonNode post(String path, Object body, String token, String key) throws Exception {
        var result = request("POST", path, body, token, key);
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        return result.path("data");
    }

    void policy(String token) throws Exception {
        if (sql.queryForObject(
                        "SELECT COUNT(*) FROM fnb_store_policy WHERE store_no=?",
                        Integer.class,
                        STORE)
                == 0)
            post(
                    "/biz/x/food/stores/" + STORE + "/policies",
                    Map.of(
                            "timezone",
                            "UTC",
                            "cutoverMinute",
                            240,
                            "effectiveFrom",
                            Instant.now()
                                    .minus(7, ChronoUnit.DAYS)
                                    .truncatedTo(ChronoUnit.MILLIS)
                                    .toString(),
                            "payPolicy",
                            "POSTPAY",
                            "capabilities",
                            List.of(),
                            "version",
                            0),
                    token,
                    key());
    }

    JsonNode table(String token) throws Exception {
        policy(token);
        String tag = key().substring(0, 8);
        String area =
                post(
                                "/biz/x/food/stores/" + STORE + "/table-areas",
                                Map.of("code", tag, "name", "native area", "sortNo", 0),
                                token,
                                key())
                        .path("area")
                        .path("areaNo")
                        .asString();
        return post(
                        "/biz/x/food/stores/" + STORE + "/tables",
                        Map.of(
                                "areaNo",
                                area,
                                "code",
                                tag,
                                "name",
                                "native table",
                                "capacity",
                                4,
                                "sortNo",
                                0),
                        token,
                        key())
                .path("table");
    }

    Map<String, Object> openBody(JsonNode table) {
        return Map.of(
                "tableNos",
                List.of(table.path("tableNo").asString()),
                "partySize",
                2,
                "payMode",
                "POSTPAY",
                "tableVersions",
                List.of(
                        Map.of(
                                "tableNo",
                                table.path("tableNo").asString(),
                                "version",
                                table.path("version").asLong())),
                "sharingMode",
                "EXCLUSIVE",
                "seats",
                List.of());
    }

    @Test
    void realTableReceptionReplaysOriginalKeyAndReadsPersistedCheckThroughHttp() throws Exception {
        String token = token();
        var table = table(token);
        var body = openBody(table);
        String key = key();
        String path = "/biz/x/food/stores/" + STORE + "/checks";
        var opened = post(path, body, token, key);
        var replay = post(path, body, token, key);
        assertThat(replay).isEqualTo(opened);
        String check = opened.path("check").path("checkNo").asString();
        assertThat(check).isNotBlank();
        var queried = request("GET", "/biz/x/food/checks/" + check, null, token, null);
        assertThat(queried.path("code").asInt()).isZero();
        assertThat(queried.path("data").path("check").path("payMode").asString())
                .isEqualTo("POSTPAY");
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_check WHERE check_no=?",
                                Integer.class,
                                check))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_table_occupancy WHERE check_no=? AND"
                                        + " ended_at IS NULL",
                                Integer.class,
                                check))
                .isEqualTo(1);
        deliverCheckEventTwice(check);
        String occupancy =
                sql.queryForObject(
                        "SELECT occupancy_no FROM fnb_table_occupancy WHERE check_no=? AND ended_at"
                                + " IS NULL",
                        String.class,
                        check);
        var cleared =
                post(
                        "/biz/x/food/checks/" + check + ":clear",
                        Map.of(
                                "version",
                                opened.path("check").path("version").asLong(),
                                "reason",
                                "native empty-check clear",
                                "occupancyNos",
                                List.of(occupancy)),
                        token,
                        key());
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_table_occupancy WHERE check_no=? AND"
                                        + " ended_at IS NULL",
                                Integer.class,
                                check))
                .isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT status FROM fnb_check WHERE check_no=?",
                                String.class,
                                check))
                .isEqualTo("VOIDED");
    }

    @Test
    void twoHttpRequestsForOnePhysicalTableCannotBothOpenChecks() throws Exception {
        String token = token();
        var table = table(token);
        var body = openBody(table);
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JsonNode> call =
                    () -> {
                        start.await(5, TimeUnit.SECONDS);
                        return request(
                                "POST",
                                "/biz/x/food/stores/" + STORE + "/checks",
                                body,
                                token,
                                key());
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            var results = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.path("code").asInt() == 0).count())
                    .isEqualTo(1);
            assertThat(results.stream().filter(r -> r.path("code").asInt() != 0).count())
                    .isEqualTo(1);
            assertThat(
                            results.stream()
                                    .filter(r -> r.path("code").asInt() != 0)
                                    .map(r -> r.path("code").asInt())
                                    .toList())
                    .doesNotContain(10500);
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM fnb_table_occupancy WHERE table_no=? AND"
                                            + " ended_at IS NULL",
                                    Integer.class,
                                    table.path("tableNo").asString()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void crossMerchantRouteRejectsBeforeNativeDatabaseWrite() throws Exception {
        String token = token();
        int before = sql.queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class);
        var response =
                request(
                        "POST",
                        "/biz/x/food/stores/ST-M0002/table-areas",
                        Map.of("code", key().substring(0, 8), "name", "forged", "sortNo", 0),
                        token,
                        key());
        assertThat(response.path("code").asInt()).isEqualTo(10403);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class))
                .isEqualTo(before);
    }

    JsonNode ok(String method, String path, Object body, String token) throws Exception {
        var r = request(method, path, body, token, key());
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }

    JsonNode cart(String token, String check) throws Exception {
        return post(
                        "/biz/x/food/stores/" + STORE + "/carts",
                        Map.of("checkNo", check, "orderSource", "POS", "fulfillment", "DINE_IN"),
                        token,
                        key())
                .path("cart");
    }

    @Order(2)
    @Test
    void realPostpayFlowPreservesOwnersAcrossAaMovesKitchenAndReturns() throws Exception {
        String token = token();
        var table = table(token);
        sql.update(
                "UPDATE mch_store SET open_hours='00:00-00:00' WHERE store_no=? AND"
                        + " entity_no='M0001'",
                STORE);
        String check =
                post("/biz/x/food/stores/" + STORE + "/checks", openBody(table), token, key())
                        .path("check")
                        .path("checkNo")
                        .asString();
        String goods = "G0001";
        // DevSeeder predates explicit store ownership; establish the original product fixture
        // owner.
        sql.update(
                "UPDATE prd_goods SET store_no=?,fulfillments=? WHERE goods_no=? AND"
                        + " entity_no=\"M0001\"",
                STORE,
                "[\"STORE_PICKUP\",\"DINE_IN\"]",
                goods);
        String sku =
                sql.queryForObject(
                        "SELECT sku_no FROM prd_sku WHERE goods_no=? LIMIT 1", String.class, goods);
        var dishBody =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "dishKind",
                                "SINGLE",
                                "prepSeconds",
                                0,
                                "presentation",
                                Map.of(),
                                "measurement",
                                Map.of(
                                        "unit",
                                        "EA",
                                        "mode",
                                        "COUNT",
                                        "scale",
                                        0,
                                        "allowedDeviation",
                                        0)));
        if (sql.queryForObject(
                        "SELECT COUNT(*) FROM fnb_dish WHERE goods_no=?", Integer.class, goods)
                > 0)
            dishBody.put(
                    "version",
                    sql.queryForObject(
                            "SELECT version FROM fnb_dish WHERE goods_no=?", Long.class, goods));
        ok("PUT", "/biz/x/food/stores/" + STORE + "/dishes/" + goods, dishBody, token);
        String recipe = recipe(token, sku);
        String menu =
                post(
                                "/biz/x/food/stores/" + STORE + "/menus",
                                Map.of(
                                        "name",
                                        "native menu",
                                        "menuType",
                                        "STANDARD",
                                        "timezone",
                                        "UTC",
                                        "priority",
                                        0),
                                token,
                                key())
                        .path("configNo")
                        .asString();
        var composed =
                ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "COMPOSITION",
                                "version",
                                0,
                                "categories",
                                List.of(
                                        Map.of(
                                                "name",
                                                "food",
                                                "sortNo",
                                                0,
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "goodsNo",
                                                                goods,
                                                                "sortNo",
                                                                0,
                                                                "enabled",
                                                                true,
                                                                "skuScope",
                                                                "EXPLICIT",
                                                                "skuNos",
                                                                List.of(sku),
                                                                "optionMode",
                                                                "INHERIT",
                                                                "optionGroupNos",
                                                                List.of(),
                                                                "skuRecipes",
                                                                List.of(
                                                                        Map.of(
                                                                                "skuNo",
                                                                                sku,
                                                                                "recipeNo",
                                                                                recipe))))))),
                        token);
        long version = composed.path("menu").path("version").asLong();
        var contexts =
                ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "CONTEXT",
                                "version",
                                version,
                                "contexts",
                                List.of(Map.of("orderSource", "POS", "fulfillment", "DINE_IN"))),
                        token);
        version = contexts.path("menu").path("version").asLong();
        var schedules =
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
                        .toList();
        var schedule =
                ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "SCHEDULE",
                                "version",
                                version,
                                "weekSchedules",
                                schedules,
                                "exceptions",
                                List.of()),
                        token);
        version = schedule.path("menu").path("version").asLong();
        var published =
                post(
                        "/biz/x/food/stores/" + STORE + "/menus/" + menu + ":publish",
                        Map.of(
                                "version",
                                version,
                                "effectiveAt",
                                Instant.now()
                                        .minusSeconds(1)
                                        .truncatedTo(ChronoUnit.MILLIS)
                                        .toString()),
                        token,
                        key());
        String revision = published.path("publication").path("revisionNo").asString();
        String item =
                sql.queryForObject(
                        "SELECT i.menu_item_no FROM fnb_menu_item i JOIN fnb_menu_category c ON"
                                + " i.category_no=c.category_no WHERE c.menu_no=? LIMIT 1",
                        String.class,
                        menu);
        var draft = cart(token, check);
        String cart = draft.path("cartNo").asString();
        var line =
                Map.of(
                        "sourceLineRef",
                        "NATIVE-LINE",
                        "menuNo",
                        menu,
                        "menuItemNo",
                        item,
                        "menuRevisionNo",
                        revision,
                        "goodsNo",
                        goods,
                        "skuNo",
                        sku,
                        "qty",
                        1,
                        "selections",
                        List.of(),
                        "bundleChoices",
                        List.of());
        var updated =
                ok(
                        "PUT",
                        "/biz/x/food/carts/" + cart + "/lines/NATIVE-LINE",
                        Map.of("version", draft.path("version").asLong(), "line", line),
                        token);
        if (sql.queryForObject(
                        "SELECT COUNT(*) FROM prd_sku_pricing_policy WHERE sku_no=? AND"
                                + " entity_no='M0001' AND store_no=?",
                        Integer.class,
                        sku,
                        STORE)
                == 0)
            context.getBean(GoodsPricingPolicyService.class)
                    .save(
                            new GoodsPricingPolicyCommands.Context(
                                    new GoodsPricingPolicyPort.Scope("MAIN", "M0001", STORE),
                                    "SF-M0001"),
                            goods,
                            sku,
                            new GoodsPricingPolicyCommands.SaveReq(0, true, TaxClass.STANDARD),
                            key());
        try (var pay =
                new FoodReadOnlyPayFixture(
                        context.getBean(ConfigServiceLocator.class), json, "food-native-only")) {
            pricingConfiguration();
            prepareStock(sku);
            var station =
                    post(
                            "/biz/x/food/stores/" + STORE + "/stations",
                            Map.of("name", "native kitchen", "stationType", "HOT"),
                            token,
                            key());
            String stationNo = station.path("configNo").asString();
            var routes =
                    ok("GET", "/biz/x/food/stores/" + STORE + "/sku-routes/" + sku, null, token);
            ok(
                    "PUT",
                    "/biz/x/food/stores/" + STORE + "/sku-routes/" + sku,
                    Map.of(
                            "version",
                            routes.path("version").asLong(),
                            "routes",
                            List.of(Map.of("stationNo", stationNo, "taskRole", "MAKE"))),
                    token);
            post(
                    "/biz/x/food/stores/" + STORE + "/stations/" + stationNo + ":publish",
                    Map.of(
                            "version",
                            station.path("version").asLong(),
                            "effectiveAt",
                            Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()),
                    token,
                    key());
            // Draft route replacement does not retract other stations' immutable publications.
            // Explicitly publish their now-empty route drafts when reusing this disposable fixture.
            for (var previous :
                    sql.queryForList(
                            "SELECT station_no,version FROM fnb_station WHERE entity_no='M0001' AND"
                                    + " store_no=? AND station_no<>?",
                            STORE,
                            stationNo))
                post(
                        "/biz/x/food/stores/"
                                + STORE
                                + "/stations/"
                                + previous.get("station_no")
                                + ":publish",
                        Map.of(
                                "version",
                                ((Number) previous.get("version")).longValue(),
                                "effectiveAt",
                                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()),
                        token,
                        key());
            assertThat(
                            context.getBean(KitchenTaskService.class)
                                    .freezeRoutes("M0001", STORE, List.of(sku))
                                    .routes())
                    .hasSize(1);
            awaitCapabilityWindow();
            var sourceFacts =
                    context.getBean(DiningPricingFactsService.class)
                            .staff("M0001", STORE, cart, updated.path("version").asLong());
            assertThat(sourceFacts).isNotNull();
            var configHead =
                    context.getBean(TradeMappers.StorePricingConfigMapper.class)
                            .selectOne(
                                    new QueryWrapper<OrdStorePricingConfig>()
                                            .eq("entity_no", "M0001")
                                            .eq("store_no", STORE));
            var storedRevision =
                    context.getBean(TradeMappers.StorePricingRevisionMapper.class)
                            .selectOne(
                                    new QueryWrapper<OrdStorePricingRevision>()
                                            .eq("revision_no", configHead.getActiveRevisionNo()));
            var templateRevision =
                    context.getBean(TradeMappers.PricingTemplateRevisionMapper.class)
                            .selectOne(
                                    new QueryWrapper<OrdPricingTemplateRevision>()
                                            .eq(
                                                    "revision_no",
                                                    storedRevision.getTemplateRevisionNo()));
            var compiledDefinition =
                    new PricingConfigCompiler()
                            .compileDefinition(
                                    templateRevision.getDefinitionSnapshot(),
                                    SystemConstraints.standard());
            assertThat(compiledDefinition.valid())
                    .as(compiledDefinition.issues().toString())
                    .isTrue();
            assertThat(json.readTree(compiledDefinition.config().canonicalJson()))
                    .isEqualTo(json.readTree(templateRevision.getDefinitionSnapshot()));
            assertThat(PricingRuntimeConfigPolicy.catalogHash(compiledDefinition.config()))
                    .as("Published rule catalog hash must survive a process restart")
                    .isEqualTo(templateRevision.getRuleCatalogVersion());
            assertThat(
                            PricingRuntimeConfigPolicy.verify(
                                    storedRevision,
                                    templateRevision,
                                    new PricingCapabilities(0, 0, false, false, false)))
                    .isNotNull();
            var runtime =
                    context.getBean(PricingRuntimeConfigService.class)
                            .resolve(
                                    new PricingSourceFactsPort.Scope("MAIN", "M0001", STORE),
                                    "FOOD");
            assertThat(runtime.config().definition().couponLimits().total()).isZero();
            var prepared =
                    context.getBean(PricingPreparationService.class)
                            .staff(
                                    new PricingSourceFactsPort.Scope("MAIN", "M0001", STORE),
                                    new PricingSourceRef(
                                            "FOOD",
                                            "CART",
                                            cart,
                                            updated.path("version").asLong(),
                                            null,
                                            null));
            assertThat(prepared).isNotNull();
            String quoteKey = key();
            var quoteBody = Map.of("cartNo", cart, "version", updated.path("version").asLong());
            var quoteResult =
                    request(
                            "POST",
                            "/biz/x/food/stores/" + STORE + "/quotes",
                            quoteBody,
                            token,
                            quoteKey);
            assertThat(quoteResult.path("code").asInt()).as(quoteResult.toString()).isZero();
            var quote = quoteResult.path("data");
            assertThat(quote.path("status").asString()).isEqualTo("READY");
            assertThat(quote.path("quoteNo").asString()).isNotBlank();
            var loggedIn =
                    post(
                            "/mp/user/login",
                            Map.of(
                                    "grantType",
                                    "WECHAT_MP",
                                    "principal",
                                    "native-food-" + key(),
                                    "agreed",
                                    true),
                            null,
                            key());
            String buyerToken = loggedIn.path("token").asString();
            var profile = request("GET", "/mp/user/profile", null, buyerToken, null);
            assertThat(profile.path("code").asInt()).as(profile.toString()).isZero();
            String buyer = profile.path("data").path("userNo").asString();
            assertThat(buyer).isNotBlank();
            String placeKey = key();
            var placeBody =
                    Map.of(
                            "buyerNo",
                            buyer,
                            "cartNo",
                            cart,
                            "version",
                            updated.path("version").asLong(),
                            "quoteNo",
                            quote.path("quoteNo").asString(),
                            "confirmationDigest",
                            quote.path("confirmationDigest").asString(),
                            "payMode",
                            "POSTPAY");
            var batchResult =
                    request(
                            "POST",
                            "/biz/x/food/stores/" + STORE + "/batches",
                            placeBody,
                            token,
                            placeKey);
            assertThat(batchResult.path("code").asInt()).as(batchResult.toString()).isZero();
            var batch = batchResult.path("data");
            assertThat(batch.path("status").asString()).as(batch.toString()).isEqualTo("ACCEPTED");
            assertThat(post("/biz/x/food/stores/" + STORE + "/batches", placeBody, token, placeKey))
                    .isEqualTo(batch);
            String batchNo = batch.path("batchNo").asString();
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM fnb_core_order_binding WHERE batch_no=?"
                                            + " AND status='BOUND'",
                                    Integer.class,
                                    batchNo))
                    .isEqualTo(1);
            assertThat(
                            sql.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    batch.path("orderNo").asString()))
                    .isEqualTo("WAIT_PAY");
            nativeBusinessSharesAndMoves(token, check, batchNo);
            var replacement = nativeKitchenProductionAndReturn(token, batchNo);
            nativeReplacementOrder(token, buyer, batch, replacement, line);
        }
    }

    void pricingConfiguration() throws Exception {
        sql.update(
                "INSERT IGNORE INTO"
                    + " sys_market(market,name,currency,currency_scale,time_zone,display_rate,enabled,sort_no)"
                    + " VALUES ('CN','Native China','CNY',2,'Asia/Shanghai',1,1,0)");
        var capabilities = new PricingCapabilities(0, 0, false, false, false);
        var platform =
                new PricingConfigCommands.Context(
                        "MAIN",
                        "NATIVE-OPS",
                        true,
                        null,
                        null,
                        capabilities,
                        "NATIVE-NO-ASSETS",
                        Instant.now().plusSeconds(600),
                        Set.of());
        var store =
                new PricingConfigCommands.Context(
                        "MAIN",
                        "SF-M0001",
                        false,
                        "M0001",
                        STORE,
                        capabilities,
                        "NATIVE-NO-ASSETS",
                        Instant.now().plusSeconds(600),
                        Set.of());
        var taxes = context.getBean(PricingTaxSettingService.class);
        if (taxes.getMerchant(store).effective() == null)
            taxes.saveMerchant(
                    store,
                    new PricingTaxSettingCommands.SaveReq(0, 0, PriceTaxMode.EXCLUSIVE),
                    key());
        if (sql.queryForObject(
                        "SELECT COUNT(*) FROM ord_store_pricing_config WHERE entity_no='M0001' AND"
                                + " store_no=?",
                        Integer.class,
                        STORE)
                > 0) return;
        var templates = context.getBean(PricingTemplateService.class);
        var stores = context.getBean(StorePricingConfigService.class);
        var seed =
                json.readTree(
                        Files.readString(
                                Path.of(
                                        "../../docs/technical/design/schema/pricing-merchant-tax-initial-data.example.json")));
        String definition = seed.path("industryTemplates").get(0).path("definition").toString();
        var draft =
                templates.create(
                        platform,
                        new PricingConfigCommands.CreateTemplateReq(
                                "FOOD",
                                "NATIVE-" + key().substring(0, 8),
                                "native food",
                                definition),
                        key());
        var preview = templates.preview(platform, draft.no(), draft.draftVersion(), null);
        var revision =
                templates.publish(
                        platform,
                        draft.no(),
                        new PricingConfigCommands.PublishReq(
                                draft.draftVersion(),
                                draft.activeRevisionNo(),
                                preview.previewHash(),
                                "native fixture",
                                null),
                        key());
        stores.initialize(
                store,
                new PricingConfigCommands.InitializeReq(
                        revision.revisionNo(),
                        "native fixture",
                        "{\"couponLimits\":{\"total\":0,\"percent\":0,\"amountTotal\":0,\"cash\":0,\"deduction\":0},\"discountCouponPolicy\":{\"maxUsedPerOrder\":0},\"allowedCouponCombinations\":[],\"storedValueEnabled\":false,\"pointsEnabled\":false,\"manualOperations\":{\"enabled\":false}}"),
                key());
    }

    void deliverCheckEventTwice(String check) throws Exception {
        String event =
                sql.queryForObject(
                        "SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND"
                                + " event_type='FOOD_CHECK_CHANGED' ORDER BY id DESC LIMIT 1",
                        String.class,
                        check);
        String handler = "food-kds-business-events-v1";
        long id =
                sql.queryForObject(
                        "SELECT id FROM sys_outbox_delivery WHERE event_no=? AND handler_id=?",
                        Long.class,
                        event,
                        handler);
        var facade = context.getBean(UnifiedOutboxFacade.class);
        var store = facade.store("PLATFORM");
        var claim =
                store.tryClaim(
                                new DeliveryRef("PLATFORM", id),
                                key(),
                                Instant.now(),
                                Duration.ofMinutes(2))
                        .orElseThrow();
        var first = facade.handler("PLATFORM", handler).handle(claim.event());
        assertThat(first.kind()).isEqualTo(DeliveryResult.Kind.SENT);
        // Simulate a consumer response lost before the durable delivery acknowledgement.
        assertThat(facade.handler("PLATFORM", handler).handle(claim.event()).kind())
                .isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(store.complete(claim, first, Instant.now(), RetryPolicy.defaults())).isTrue();
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_kds_event WHERE event_no=?",
                                Integer.class,
                                event))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT status FROM sys_outbox_delivery WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("SENT");
    }

    @Test
    void concurrentPhysicalTransfersHaveOneWinnerAndRollbackLosingTargetOccupancy()
            throws Exception {
        String token = token();
        var original = table(token);
        var targetA = table(token);
        var targetB = table(token);
        var opened =
                post("/biz/x/food/stores/" + STORE + "/checks", openBody(original), token, key())
                        .path("check");
        String check = opened.path("checkNo").asString();
        long sourceVersion =
                sql.queryForObject(
                        "SELECT version FROM fnb_table WHERE table_no=?",
                        Long.class,
                        original.path("tableNo").asString());
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Function<JsonNode, Callable<JsonNode>> call =
                    target ->
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return request(
                                        "POST",
                                        "/biz/x/food/checks/" + check + ":transfer",
                                        Map.of(
                                                "version",
                                                opened.path("version").asLong(),
                                                "reason",
                                                "native transfer",
                                                "targetTableNos",
                                                List.of(target.path("tableNo").asString()),
                                                "tableVersions",
                                                List.of(
                                                        Map.of(
                                                                "tableNo",
                                                                original.path("tableNo").asString(),
                                                                "version",
                                                                sourceVersion),
                                                        Map.of(
                                                                "tableNo",
                                                                target.path("tableNo").asString(),
                                                                "version",
                                                                target.path("version").asLong()))),
                                        token,
                                        key());
                            };
            var a = pool.submit(call.apply(targetA));
            var b = pool.submit(call.apply(targetB));
            var results = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().map(r -> r.path("code").asInt()).sorted().toList())
                    .containsExactly(0, 10409);
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM fnb_table_occupancy WHERE check_no=? AND"
                                            + " ended_at IS NULL",
                                    Integer.class,
                                    check))
                    .isEqualTo(1);
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM fnb_table_occupancy WHERE table_no=? AND"
                                            + " ended_at IS NULL",
                                    Integer.class,
                                    original.path("tableNo").asString()))
                    .isZero();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Order(1)
    @Test
    void legacyCatalogRecoveryCreatesNewPublicationsAndPreservesOldRevisionContents()
            throws Exception {
        try (var pay =
                new FoodReadOnlyPayFixture(
                        context.getBean(ConfigServiceLocator.class), json, "food-native-only")) {
            pricingConfiguration();
            var caps = new PricingCapabilities(0, 0, false, false, false);
            var platform =
                    new PricingConfigCommands.Context(
                            "MAIN",
                            "NATIVE-OPS",
                            true,
                            null,
                            null,
                            caps,
                            "NATIVE-NO-ASSETS",
                            Instant.now().plusSeconds(600),
                            Set.of());
            var store =
                    new PricingConfigCommands.Context(
                            "MAIN",
                            "SF-M0001",
                            false,
                            "M0001",
                            STORE,
                            caps,
                            "NATIVE-NO-ASSETS",
                            Instant.now().plusSeconds(600),
                            Set.of());
            var head =
                    sql.queryForMap(
                            "SELECT config_no,template_no,active_revision_no FROM"
                                    + " ord_store_pricing_config WHERE entity_no='M0001' AND"
                                    + " store_no=?",
                            STORE);
            String config = head.get("config_no").toString(),
                    template = head.get("template_no").toString(),
                    oldStore = head.get("active_revision_no").toString();
            String oldTemplate =
                    sql.queryForObject(
                            "SELECT template_revision_no FROM ord_store_pricing_revision WHERE"
                                    + " revision_no=?",
                            String.class,
                            oldStore);
            // Fixture reproduces the digest observed before the EnumSet fix; no rule content or
            // authority changes.
            String legacy = "1f074b3c2ca948b3cd61989e3f00f66d3cb5e5ce1f6b725599c4701057b13999";
            sql.update(
                    "UPDATE ord_pricing_template_revision SET rule_catalog_version=? WHERE"
                            + " revision_no=?",
                    legacy,
                    oldTemplate);
            sql.update(
                    "UPDATE ord_store_pricing_revision SET rule_catalog_version=? WHERE"
                            + " revision_no=?",
                    legacy,
                    oldStore);
            var oldTemplateRow =
                    sql.queryForMap(
                            "SELECT * FROM ord_pricing_template_revision WHERE revision_no=?",
                            oldTemplate);
            var oldStoreRow =
                    sql.queryForMap(
                            "SELECT * FROM ord_store_pricing_revision WHERE revision_no=?",
                            oldStore);
            var runtime = context.getBean(PricingRuntimeConfigService.class);
            var scope = new PricingSourceFactsPort.Scope("MAIN", "M0001", STORE);
            Assertions.assertThatThrownBy(() -> runtime.resolve(scope, "FOOD"))
                    .isInstanceOf(BizException.class);
            var templates = context.getBean(PricingTemplateService.class);
            var stores = context.getBean(StorePricingConfigService.class);
            var draft = templates.getDraft(platform, template);
            var preview = templates.preview(platform, template, draft.draftVersion(), null);
            var next =
                    templates.publish(
                            platform,
                            template,
                            new PricingConfigCommands.PublishReq(
                                    draft.draftVersion(),
                                    draft.activeRevisionNo(),
                                    preview.previewHash(),
                                    "explicit native catalog recovery",
                                    null),
                            key());
            var storeDraft = stores.getDraft(store, config);
            stores.upgradeTemplate(
                    store,
                    config,
                    new PricingConfigCommands.UpgradeReq(
                            storeDraft.draftVersion(), next.revisionNo()),
                    key());
            storeDraft = stores.getDraft(store, config);
            var storePreview = stores.preview(store, config, storeDraft.draftVersion(), null);
            var published =
                    stores.publish(
                            store,
                            config,
                            new PricingConfigCommands.PublishReq(
                                    storeDraft.draftVersion(),
                                    storeDraft.activeRevisionNo(),
                                    storePreview.previewHash(),
                                    "explicit native catalog recovery",
                                    null),
                            key());
            assertThat(published.revisionNo()).isNotEqualTo(oldStore);
            assertThat(runtime.resolve(scope, "FOOD").revisionNo())
                    .isEqualTo(published.revisionNo());
            assertThat(
                            sql.queryForMap(
                                    "SELECT * FROM ord_pricing_template_revision WHERE"
                                            + " revision_no=?",
                                    oldTemplate))
                    .isEqualTo(oldTemplateRow);
            assertThat(
                            sql.queryForMap(
                                    "SELECT * FROM ord_store_pricing_revision WHERE revision_no=?",
                                    oldStore))
                    .isEqualTo(oldStoreRow);
        }
    }

    String prepareStock(String sku) {
        return prepareStock(sku, "COUNT");
    }

    String prepareStock(String sku, String unit) {
        var acl = context.getBean(InventoryAclService.class);
        String owner = acl.ownerIdOf("M0001");
        String item = acl.upsertItem("M0001", sku, "native food", "fixture", null, null, unit);
        acl.locationIdOf("M0001", STORE);
        String location = acl.stockLocationOfExistingStore("M0001", STORE);
        assertThat(location).isNotBlank();
        var posted =
                context.getBean(StockPostingService.class)
                        .post(
                                new StockPostingService.PostingDoc(
                                        owner,
                                        "IN",
                                        "NATIVE-" + key().substring(0, 20),
                                        "PURCHASE",
                                        LocalDateTime.now(ZoneOffset.UTC),
                                        "NATIVE",
                                        List.of(
                                                new StockPostingService.Line(
                                                        1, item, location, 10, 100L, null, null,
                                                        "CNY", null))));
        assertThat(posted).hasSize(1);
        return item;
    }

    long checkVersion(String check) {
        return sql.queryForObject(
                "SELECT version FROM fnb_check WHERE check_no=?", Long.class, check);
    }

    List<JsonNode> race(Callable<JsonNode> first, Callable<JsonNode> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return first.call();
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return second.call();
                            });
            var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().map(r -> r.path("code").asInt()).sorted().toList())
                    .containsExactly(0, 10409);
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    void nativeBusinessSharesAndMoves(String token, String check, String batch) throws Exception {
        String aa = "/biz/x/food/checks/" + check + "/business-share-plans";
        var plan =
                post(
                        aa,
                        Map.of(
                                "checkVersion",
                                checkVersion(check),
                                "mode",
                                "BY_PERSON",
                                "people",
                                List.of(Map.of("personNo", "P1"), Map.of("personNo", "P2")),
                                "reason",
                                "native business split"),
                        token,
                        key());
        assertThat(plan.path("financialAuthorityPending").asBoolean()).isTrue();
        assertThat(plan.path("settlementEligible").asBoolean()).isFalse();
        var confirm =
                Map.of(
                        "version",
                        plan.path("version").asLong(),
                        "sourceHash",
                        plan.path("sourceHash").asString(),
                        "planHash",
                        plan.path("planHash").asString());
        String path = aa + "/" + plan.path("planNo").asString() + ":confirm";
        race(
                () -> request("POST", path, confirm, token, key()),
                () -> request("POST", path, confirm, token, key()));
        var first = table(token);
        var second = table(token);
        String a =
                post("/biz/x/food/stores/" + STORE + "/checks", openBody(first), token, key())
                        .path("check")
                        .path("checkNo")
                        .asString();
        String b =
                post("/biz/x/food/stores/" + STORE + "/checks", openBody(second), token, key())
                        .path("check")
                        .path("checkNo")
                        .asString();
        long v = checkVersion(check), av = checkVersion(a), bv = checkVersion(b);
        BiFunction<String, Long, Object> body =
                (target, version) ->
                        Map.of(
                                "request",
                                Map.of(
                                        "source",
                                        Map.of("checkNo", check, "version", v),
                                        "target",
                                        Map.of("checkNo", target, "version", version),
                                        "batchNos",
                                        List.of(batch),
                                        "reason",
                                        "native batch transfer"));
        String move = "/biz/x/food/business-operations/checkCollaboration/moveBatches";
        race(
                () -> request("POST", move, body.apply(a, av), token, key()),
                () -> request("POST", move, body.apply(b, bv), token, key()));
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_check_batch_membership WHERE batch_no=?"
                                        + " AND detached_at IS NULL",
                                Integer.class,
                                batch))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT check_no FROM fnb_order_batch WHERE batch_no=?",
                                String.class,
                                batch))
                .isEqualTo(check);
        String current =
                sql.queryForObject(
                        "SELECT check_no FROM fnb_check_batch_membership WHERE batch_no=? AND"
                                + " detached_at IS NULL",
                        String.class,
                        batch);
        assertThat(current).isIn(a, b);
        String line =
                sql.queryForObject(
                        "SELECT food_line_no FROM fnb_order_line WHERE batch_no=? LIMIT 1",
                        String.class,
                        batch);
        String target = current.equals(a) ? b : a;
        var lineBody =
                Map.of(
                        "request",
                        Map.of(
                                "source",
                                Map.of("checkNo", current, "version", checkVersion(current)),
                                "target",
                                Map.of("checkNo", target, "version", checkVersion(target)),
                                "foodLineNos",
                                List.of(line),
                                "reason",
                                "native line transfer"));
        String lineKey = key();
        String linePath = "/biz/x/food/business-operations/checkCollaboration/moveLines";
        var moved = post(linePath, lineBody, token, lineKey);
        assertThat(post(linePath, lineBody, token, lineKey)).isEqualTo(moved);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_check_line_membership WHERE"
                                        + " food_line_no=? AND detached_at IS NULL",
                                Integer.class,
                                line))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT check_no FROM fnb_check_line_membership WHERE"
                                        + " food_line_no=? AND detached_at IS NULL",
                                String.class,
                                line))
                .isEqualTo(target);
    }

    @Test
    void anOpenCartStillBlocksEmptyPostpayClearAndKeepsItsOccupancy() throws Exception {
        String token = token();
        var table = table(token);
        var opened =
                post("/biz/x/food/stores/" + STORE + "/checks", openBody(table), token, key())
                        .path("check");
        String check = opened.path("checkNo").asString();
        cart(token, check);
        String occupancy =
                sql.queryForObject(
                        "SELECT occupancy_no FROM fnb_table_occupancy WHERE check_no=? AND ended_at"
                                + " IS NULL",
                        String.class,
                        check);
        var r =
                request(
                        "POST",
                        "/biz/x/food/checks/" + check + ":clear",
                        Map.of(
                                "version",
                                checkVersion(check),
                                "reason",
                                "must not clear open draft",
                                "occupancyNos",
                                List.of(occupancy)),
                        token,
                        key());
        assertThat(r.path("code").asInt()).isEqualTo(10409);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_table_occupancy WHERE occupancy_no=? AND"
                                        + " ended_at IS NULL",
                                Integer.class,
                                occupancy))
                .isEqualTo(1);
    }

    String recipe(String token, String sku) throws Exception {
        String ingredient = prepareStock("SK0003");
        var draft =
                post(
                                "/biz/x/food/stores/" + STORE + "/recipes",
                                Map.of(
                                        "skuNo",
                                        sku,
                                        "recipeCode",
                                        "N_" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "yieldQty",
                                        1,
                                        "yieldUnit",
                                        "EA"),
                                token,
                                key())
                        .path("recipe");
        String number = draft.path("recipeNo").asString();
        assertThat(number).isNotBlank();
        var definition =
                ok(
                                "PUT",
                                "/biz/x/food/recipes/" + number + "/definition",
                                Map.of(
                                        "version",
                                        draft.path("version").asLong(),
                                        "yieldQty",
                                        1,
                                        "yieldUnit",
                                        "EA",
                                        "lines",
                                        List.of(
                                                Map.of(
                                                        "itemRef",
                                                        ingredient,
                                                        "qty",
                                                        1,
                                                        "unit",
                                                        "COUNT",
                                                        "lossRate",
                                                        0))),
                                token)
                        .path("recipe");
        post(
                "/biz/x/food/recipes/" + number + ":publish",
                Map.of(
                        "version",
                        definition.path("version").asLong(),
                        "effectiveAt",
                        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()),
                token,
                key());
        return number;
    }

    ReplacementContext nativeKitchenProductionAndReturn(String token, String batch)
            throws Exception {
        String accepted =
                sql.queryForObject(
                        "SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND"
                                + " event_type='DINING_ORDER_ACCEPTED' ORDER BY id DESC LIMIT 1",
                        String.class,
                        batch);
        deliver(accepted, FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER);
        String task =
                sql.queryForObject(
                        "SELECT t.task_no FROM fnb_kitchen_task t JOIN fnb_kitchen_ticket k ON"
                            + " k.ticket_no=t.ticket_no WHERE k.batch_no=? AND k.kind='MAKE' LIMIT"
                            + " 1",
                        String.class,
                        batch);
        var started =
                post(
                        "/biz/x/food/tasks/" + task + ":start",
                        Map.of(
                                "version",
                                taskVersion(task),
                                "qty",
                                1,
                                "reason",
                                "native preparation"),
                        token,
                        key());
        String event = started.path("eventNo").asString();
        assertThat(event).isNotBlank();
        var consumed =
                post("/biz/x/food/production-events/" + event + ":consume", Map.of(), token, key());
        assertThat(consumed.path("status").asString())
                .as(consumed.toString())
                .isEqualTo("CONFIRMED");
        productionLostResponse(token, task);
        post(
                "/biz/x/food/tasks/" + task + ":ready",
                Map.of("version", taskVersion(task), "qty", 1, "reason", "native ready"),
                token,
                key());
        String line =
                sql.queryForObject(
                        "SELECT food_line_no FROM fnb_order_line WHERE batch_no=? LIMIT 1",
                        String.class,
                        batch);
        String handoverPath = "/biz/x/food/batches/" + batch + ":handover", handoverKey = key();
        var handoverBody =
                Map.of(
                        "kind",
                        "DINE_IN",
                        "lines",
                        List.of(Map.of("foodLineNo", line, "qty", 1)),
                        "reason",
                        "native customer handover");
        var handed = post(handoverPath, handoverBody, token, handoverKey);
        assertThat(handed.has("deliveryPending")).isTrue();
        assertThat(handed.path("deliveryPending").asBoolean()).as(handed.toString()).isFalse();
        assertThat(handed.path("coreDeliveryRef").asString()).isNotBlank();
        assertThat(post(handoverPath, handoverBody, token, handoverKey)).isEqualTo(handed);
        var returned =
                post(
                        "/biz/x/food/batches/" + batch + "/business-returns",
                        Map.of(
                                "batchVersion",
                                sql.queryForObject(
                                        "SELECT version FROM fnb_order_batch WHERE batch_no=?",
                                        Long.class,
                                        batch),
                                "lines",
                                List.of(Map.of("foodLineNo", line, "qty", 1)),
                                "reason",
                                "native return"),
                        token,
                        key());
        assertThat(returned.path("refundRequested").asBoolean()).isFalse();
        assertThat(returned.path("originalPayableChanged").asBoolean()).isFalse();
        String number = returned.path("returnNo").asString();
        String approvalPath = "/biz/x/food/business-returns/" + number + ":approve";
        String originalCheck =
                sql.queryForObject(
                        "SELECT check_no FROM fnb_order_batch WHERE batch_no=?",
                        String.class,
                        batch);
        var replacementCart = cart(token, originalCheck);
        var approvalBody =
                Map.of(
                        "version",
                        returned.path("version").asLong(),
                        "reason",
                        "native replacement approval",
                        "replacementCartNo",
                        replacementCart.path("cartNo").asString());
        String approvalKey = key();
        var waiting = post(approvalPath, approvalBody, token, approvalKey);
        assertThat(waiting.path("pending").asBoolean()).isTrue();
        var kitchen = ok("GET", "/biz/x/food/returns/" + number + "/kitchen", null, token);
        var decisions = new ArrayList<Map<String, String>>();
        for (var i : kitchen.path("items"))
            decisions.add(
                    Map.of(
                            "returnItemNo",
                            i.path("returnItemNo").asString(),
                            "disposition",
                            "KEEP"));
        post(
                "/biz/x/food/returns/" + number + "/kitchen",
                Map.of(
                        "version",
                        kitchen.path("version").asLong(),
                        "decisions",
                        decisions,
                        "reason",
                        "native explicit keep"),
                token,
                key());
        var results =
                raceAllowReplay(
                        () -> request("POST", approvalPath, approvalBody, token, approvalKey),
                        () -> request("POST", approvalPath, approvalBody, token, approvalKey));
        assertThat(results.stream().map(r -> r.path("code").asInt()).toList())
                .contains(0)
                .containsOnly(0, 10409);
        for (var r : results)
            if (r.path("code").asInt() == 0) {
                assertThat(r.path("data").path("pending").asBoolean()).isFalse();
                assertThat(r.path("data").path("originalPayableChanged").asBoolean()).isTrue();
            }
        var confirmed = post(approvalPath, approvalBody, token, approvalKey);
        assertThat(confirmed.path("pending").asBoolean()).isFalse();
        assertThat(confirmed.path("originalPayableChanged").asBoolean()).isTrue();
        assertThat(post(approvalPath, approvalBody, token, approvalKey)).isEqualTo(confirmed);
        assertThat(request("POST", approvalPath, approvalBody, token, key()).path("code").asInt())
                .isEqualTo(10409);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_return_amendment WHERE return_no=?",
                                Long.class,
                                number))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_industry_amendment WHERE"
                                        + " source_return_no=? AND status='CONFIRMED'",
                                Long.class,
                                number))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_industry_receivable_adjustment a JOIN"
                                    + " ord_industry_amendment m ON a.amendment_no=m.amendment_no"
                                    + " WHERE m.source_return_no=?",
                                Long.class,
                                number))
                .isEqualTo(1);
        return new ReplacementContext(number, replacementCart, confirmed);
    }

    long taskVersion(String task) {
        return sql.queryForObject(
                "SELECT version FROM fnb_kitchen_task WHERE task_no=?", Long.class, task);
    }

    void deliver(String event, String handler) throws Exception {
        long id =
                sql.queryForObject(
                        "SELECT id FROM sys_outbox_delivery WHERE event_no=? AND handler_id=?",
                        Long.class,
                        event,
                        handler);
        var facade = context.getBean(UnifiedOutboxFacade.class);
        var store = facade.store("PLATFORM");
        var claim =
                store.tryClaim(
                                new DeliveryRef("PLATFORM", id),
                                key(),
                                Instant.now(),
                                Duration.ofMinutes(2))
                        .orElseThrow();
        var result = facade.handler("PLATFORM", handler).handle(claim.event());
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(store.complete(claim, result, Instant.now(), RetryPolicy.defaults())).isTrue();
    }

    List<JsonNode> raceAllowReplay(Callable<JsonNode> first, Callable<JsonNode> second)
            throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return first.call();
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return second.call();
                            });
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    void productionLostResponse(String token, String task) throws Exception {
        // A transport failure is injected AFTER the real inventory owner commits, never instead of
        // it.
        var owner = (Advised) context.getBean(InventoryProductionPortImpl.class);
        var armed = new AtomicReference<String>("consume");
        MethodInterceptor drop =
                invocation -> {
                    var receipt = invocation.proceed();
                    if (invocation.getMethod().getName().equals(armed.get())
                            && armed.compareAndSet(invocation.getMethod().getName(), null))
                        throw new IllegalStateException("native lost inventory response");
                    return receipt;
                };
        owner.addAdvice(0, drop);
        var inv = new JdbcTemplate(context.getBean("invDataSource", DataSource.class));
        long ledgerBefore = inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class);
        try {
            String path = "/biz/x/food/production-tasks/" + task + ":adjust", key = key();
            var body =
                    Map.of(
                            "version",
                            taskVersion(task),
                            "kind",
                            "MATERIAL_SUPPLEMENT",
                            "qty",
                            1,
                            "reason",
                            "native supplemental ingredients");
            var lost = request("POST", path, body, token, key);
            assertThat(lost.path("code").asInt()).isEqualTo(10500);
            assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class))
                    .isEqualTo(ledgerBefore + 1);
            String originalEvent =
                    sql.queryForObject(
                            "SELECT c.source_event_no FROM fnb_production_consumption c JOIN"
                                    + " fnb_service_event e ON c.source_event_no=e.event_no WHERE"
                                    + " e.task_no=? AND e.action='MATERIAL_SUPPLEMENT'",
                            String.class,
                            task);
            assertThat(
                            sql.queryForObject(
                                    "SELECT status FROM fnb_production_consumption WHERE"
                                            + " source_event_no=?",
                                    String.class,
                                    originalEvent))
                    .isEqualTo("UNKNOWN");
            var recovered = post(path, body, token, key);
            assertThat(recovered.path("status").asString())
                    .as(recovered.toString())
                    .isEqualTo("CONFIRMED");
            assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class))
                    .isEqualTo(ledgerBefore + 1);
            String event = recovered.path("sourceEventNo").asString();
            assertThat(event).isEqualTo(originalEvent);
            assertThat(
                            inv.queryForObject(
                                    "SELECT COUNT(*) FROM inv_production_operation WHERE"
                                            + " event_no=?",
                                    Long.class,
                                    event))
                    .isEqualTo(1);
            String reverse = "/biz/x/food/production-events/" + event + ":reverse",
                    reverseKey = key();
            var reverseBody =
                    Map.of(
                            "version",
                            recovered.path("version").asLong(),
                            "reason",
                            "native correction");
            armed.set("reverse");
            assertThat(
                            request("POST", reverse, reverseBody, token, reverseKey)
                                    .path("code")
                                    .asInt())
                    .isEqualTo(10500);
            assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class))
                    .isEqualTo(ledgerBefore + 2);
            assertThat(
                            sql.queryForObject(
                                    "SELECT status FROM fnb_production_consumption WHERE"
                                            + " source_event_no=?",
                                    String.class,
                                    event))
                    .isEqualTo("UNKNOWN");
            var reversed = post(reverse, reverseBody, token, reverseKey);
            assertThat(reversed.path("status").asString())
                    .as(reversed.toString())
                    .isEqualTo("REVERSED");
            assertThat(post(reverse, reverseBody, token, reverseKey)).isEqualTo(reversed);
            assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class))
                    .isEqualTo(ledgerBefore + 2);
            assertThat(
                            inv.queryForObject(
                                    "SELECT COUNT(*) FROM inv_production_operation WHERE event_no=?"
                                            + " AND reverse_event_no IS NOT NULL",
                                    Long.class,
                                    event))
                    .isEqualTo(1);
        } finally {
            owner.removeAdvice(drop);
        }
    }

    @Test
    void futureBusinessDayPreviewCannotBecomeAnEarlySignedClose() throws Exception {
        String token = token();
        policy(token);
        String date = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        String openKey = key(), path = "/biz/x/food/business-days/" + date + ":open";
        var day = post(path, Map.of(), token, openKey);
        assertThat(post(path, Map.of(), token, openKey)).isEqualTo(day);
        String previewKey = key();
        var previewBody = Map.of("businessDate", date, "currency", "CNY");
        var preview = post("/biz/x/food/closes:preview", previewBody, token, previewKey);
        assertThat(post("/biz/x/food/closes:preview", previewBody, token, previewKey))
                .isEqualTo(preview);
        var denied =
                request(
                        "POST",
                        "/biz/x/food/closes:sign",
                        Map.of(
                                "closeNo",
                                preview.path("closeNo").asString(),
                                "snapshotHash",
                                preview.path("snapshotHash").asString()),
                        token,
                        key());
        assertThat(denied.path("code").asInt()).isEqualTo(10409);
        assertThat(
                        sql.queryForObject(
                                "SELECT signed_at FROM fnb_close_run WHERE close_no=?",
                                Object.class,
                                preview.path("closeNo").asString()))
                .isNull();
    }

    void awaitCapabilityWindow() throws InterruptedException {
        // The real capability owner expires evidence at five-minute UTC boundaries.
        // Start this long, multi-read scenario with at least a minute remaining; no race uses
        // sleeps.
        long remaining = 300 - Math.floorMod(Instant.now().getEpochSecond(), 300);
        if (remaining < 60) TimeUnit.MILLISECONDS.sleep(remaining * 1000 + 50);
    }

    record ReplacementContext(String returnNo, JsonNode cart, JsonNode approval) {}

    void nativeReplacementOrder(
            String token,
            String buyer,
            JsonNode original,
            ReplacementContext replacement,
            Map<String, ?> sourceLine)
            throws Exception {
        String originalOrder = original.path("orderNo").asString();
        var oldItems =
                sql.queryForList(
                        "SELECT * FROM ord_item WHERE order_no=? ORDER BY id", originalOrder);
        assertThat(oldItems).isNotEmpty();
        String cartNo = replacement.cart().path("cartNo").asString();
        var line = new LinkedHashMap<String, Object>(sourceLine);
        line.put("sourceLineRef", "REPLACEMENT-LINE");
        var changed =
                ok(
                        "PUT",
                        "/biz/x/food/carts/" + cartNo + "/lines/REPLACEMENT-LINE",
                        Map.of(
                                "version",
                                replacement.cart().path("version").asLong(),
                                "line",
                                line),
                        token);
        awaitCapabilityWindow();
        var quote =
                post(
                        "/biz/x/food/stores/" + STORE + "/quotes",
                        Map.of("cartNo", cartNo, "version", changed.path("version").asLong()),
                        token,
                        key());
        var placed =
                post(
                        "/biz/x/food/stores/" + STORE + "/batches",
                        Map.of(
                                "buyerNo",
                                buyer,
                                "cartNo",
                                cartNo,
                                "version",
                                changed.path("version").asLong(),
                                "quoteNo",
                                quote.path("quoteNo").asString(),
                                "confirmationDigest",
                                quote.path("confirmationDigest").asString(),
                                "payMode",
                                "POSTPAY"),
                        token,
                        key());
        assertThat(placed.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(
                        sql.queryForObject(
                                "SELECT check_no FROM fnb_order_batch WHERE batch_no=?",
                                String.class,
                                placed.path("batchNo").asString()))
                .isEqualTo(
                        sql.queryForObject(
                                "SELECT check_no FROM fnb_order_batch WHERE batch_no=?",
                                String.class,
                                original.path("batchNo").asString()));
        assertThat(placed.path("orderNo").asString()).isNotEqualTo(originalOrder);
        String path =
                "/biz/x/food/business-returns/" + replacement.returnNo() + ":link-replacement";
        var body =
                Map.of(
                        "version",
                        replacement.approval().path("version").asLong(),
                        "replacementBatchNo",
                        placed.path("batchNo").asString(),
                        "reason",
                        "native actual replacement order");
        assertThat(
                        request(
                                        "POST",
                                        path,
                                        Map.of(
                                                "version",
                                                replacement.approval().path("version").asLong() - 1,
                                                "replacementBatchNo",
                                                placed.path("batchNo").asString(),
                                                "reason",
                                                "native actual replacement order"),
                                        token,
                                        key())
                                .path("code")
                                .asInt())
                .isEqualTo(10409);
        String linkKey = key();
        var linked = post(path, body, token, linkKey);
        assertThat(linked.path("replacementOrderNo").asString())
                .isEqualTo(placed.path("orderNo").asString());
        assertThat(post(path, body, token, linkKey)).isEqualTo(linked);
        assertThat(request("POST", path, body, token, key()).path("code").asInt()).isEqualTo(10409);
        assertThat(
                        sql.queryForList(
                                "SELECT * FROM ord_item WHERE order_no=? ORDER BY id",
                                originalOrder))
                .isEqualTo(oldItems);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE order_no IN (?,?) AND"
                                        + " status='WAIT_PAY' AND paid_at IS NULL",
                                Integer.class,
                                originalOrder,
                                placed.path("orderNo").asString()))
                .isEqualTo(2);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_return_amendment WHERE return_no=? AND"
                                        + " replacement_batch_no=? AND replacement_order_no=?",
                                Integer.class,
                                replacement.returnNo(),
                                placed.path("batchNo").asString(),
                                placed.path("orderNo").asString()))
                .isEqualTo(1);
    }

    @Order(3)
    @Test
    void realMultiSkuOrderRoutesBothStationsAndRetainsExactConsumptionAfterReturn()
            throws Exception {
        String token = token();
        var table = table(token);
        sql.update(
                "UPDATE mch_store SET open_hours='00:00-00:00' WHERE store_no=? AND"
                        + " entity_no='M0001'",
                STORE);
        String check =
                post("/biz/x/food/stores/" + STORE + "/checks", openBody(table), token, key())
                        .path("check")
                        .path("checkNo")
                        .asString();
        // Original DevSeeder supplies two distinct SKUs of the same merchant-owned product.
        sql.update(
                "UPDATE prd_goods SET store_no=?,fulfillments=? WHERE goods_no='G0001' AND"
                        + " entity_no='M0001'",
                STORE,
                "[\"STORE_PICKUP\",\"DINE_IN\"]");
        var dish =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "dishKind",
                                "SINGLE",
                                "prepSeconds",
                                0,
                                "presentation",
                                Map.of(),
                                "measurement",
                                Map.of(
                                        "unit",
                                        "EA",
                                        "mode",
                                        "COUNT",
                                        "scale",
                                        0,
                                        "allowedDeviation",
                                        0)));
        var versions =
                sql.queryForList("SELECT version FROM fnb_dish WHERE goods_no='G0001'", Long.class);
        if (!versions.isEmpty()) dish.put("version", versions.getFirst());
        ok("PUT", "/biz/x/food/stores/" + STORE + "/dishes/G0001", dish, token);
        var skus = List.of("SK0001", "SK0002");
        var recipes = new ArrayList<Map<String, String>>();
        var stationNos = new ArrayList<String>();
        for (String sku : skus) {
            prepareStock(sku);
            recipes.add(Map.of("skuNo", sku, "recipeNo", recipe(token, sku)));
            if (sql.queryForObject(
                            "SELECT COUNT(*) FROM prd_sku_pricing_policy WHERE sku_no=? AND"
                                    + " entity_no='M0001' AND store_no=?",
                            Integer.class,
                            sku,
                            STORE)
                    == 0)
                context.getBean(GoodsPricingPolicyService.class)
                        .save(
                                new GoodsPricingPolicyCommands.Context(
                                        new GoodsPricingPolicyPort.Scope("MAIN", "M0001", STORE),
                                        "SF-M0001"),
                                "G0001",
                                sku,
                                new GoodsPricingPolicyCommands.SaveReq(0, true, TaxClass.STANDARD),
                                key());
            var station =
                    post(
                            "/biz/x/food/stores/" + STORE + "/stations",
                            Map.of("name", "multi " + sku, "stationType", "HOT"),
                            token,
                            key());
            String stationNo = station.path("configNo").asString();
            stationNos.add(stationNo);
            var routes =
                    ok("GET", "/biz/x/food/stores/" + STORE + "/sku-routes/" + sku, null, token);
            ok(
                    "PUT",
                    "/biz/x/food/stores/" + STORE + "/sku-routes/" + sku,
                    Map.of(
                            "version",
                            routes.path("version").asLong(),
                            "routes",
                            List.of(Map.of("stationNo", stationNo, "taskRole", "MAKE"))),
                    token);
        }
        // Publish the changed route drafts, including retractions from earlier fixture stations.
        for (var station :
                sql.queryForList(
                        "SELECT station_no,version FROM fnb_station WHERE entity_no='M0001' AND"
                                + " store_no=?",
                        STORE))
            post(
                    "/biz/x/food/stores/"
                            + STORE
                            + "/stations/"
                            + station.get("station_no")
                            + ":publish",
                    Map.of(
                            "version",
                            ((Number) station.get("version")).longValue(),
                            "effectiveAt",
                            Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()),
                    token,
                    key());
        String menu =
                post(
                                "/biz/x/food/stores/" + STORE + "/menus",
                                Map.of(
                                        "name",
                                        "multi sku menu",
                                        "menuType",
                                        "STANDARD",
                                        "timezone",
                                        "UTC",
                                        "priority",
                                        0),
                                token,
                                key())
                        .path("configNo")
                        .asString();
        var composed =
                ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "COMPOSITION",
                                "version",
                                0,
                                "categories",
                                List.of(
                                        Map.of(
                                                "name",
                                                "multi",
                                                "sortNo",
                                                0,
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "goodsNo",
                                                                "G0001",
                                                                "sortNo",
                                                                0,
                                                                "enabled",
                                                                true,
                                                                "skuScope",
                                                                "EXPLICIT",
                                                                "skuNos",
                                                                skus,
                                                                "optionMode",
                                                                "INHERIT",
                                                                "optionGroupNos",
                                                                List.of(),
                                                                "skuRecipes",
                                                                recipes))))),
                        token);
        var contexts =
                ok(
                        "PUT",
                        "/biz/x/food/menus/" + menu + "/definition",
                        Map.of(
                                "section",
                                "CONTEXT",
                                "version",
                                composed.path("menu").path("version").asLong(),
                                "contexts",
                                List.of(Map.of("orderSource", "POS", "fulfillment", "DINE_IN"))),
                        token);
        var schedule =
                ok(
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
                post(
                                "/biz/x/food/stores/" + STORE + "/menus/" + menu + ":publish",
                                Map.of(
                                        "version",
                                        schedule.path("menu").path("version").asLong(),
                                        "effectiveAt",
                                        Instant.now()
                                                .minusSeconds(1)
                                                .truncatedTo(ChronoUnit.MILLIS)
                                                .toString()),
                                token,
                                key())
                        .path("publication")
                        .path("revisionNo")
                        .asString();
        String menuItem =
                sql.queryForObject(
                        "SELECT i.menu_item_no FROM fnb_menu_item i JOIN fnb_menu_category c ON"
                                + " i.category_no=c.category_no WHERE c.menu_no=?",
                        String.class,
                        menu);
        var cart = cart(token, check);
        for (int i = 0; i < skus.size(); i++) {
            String lineRef = "MULTI-" + i;
            cart =
                    ok(
                            "PUT",
                            "/biz/x/food/carts/"
                                    + cart.path("cartNo").asString()
                                    + "/lines/"
                                    + lineRef,
                            Map.of(
                                    "version",
                                    cart.path("version").asLong(),
                                    "line",
                                    Map.of(
                                            "sourceLineRef",
                                            lineRef,
                                            "menuNo",
                                            menu,
                                            "menuItemNo",
                                            menuItem,
                                            "menuRevisionNo",
                                            revision,
                                            "goodsNo",
                                            "G0001",
                                            "skuNo",
                                            skus.get(i),
                                            "qty",
                                            i + 1,
                                            "selections",
                                            List.of(),
                                            "bundleChoices",
                                            List.of())),
                            token);
        }
        try (var pay =
                new FoodReadOnlyPayFixture(
                        context.getBean(ConfigServiceLocator.class), json, "food-native-only")) {
            pricingConfiguration();
            awaitCapabilityWindow();
            var quote =
                    post(
                            "/biz/x/food/stores/" + STORE + "/quotes",
                            Map.of(
                                    "cartNo",
                                    cart.path("cartNo").asString(),
                                    "version",
                                    cart.path("version").asLong()),
                            token,
                            key());
            var login =
                    post(
                            "/mp/user/login",
                            Map.of(
                                    "grantType",
                                    "WECHAT_MP",
                                    "principal",
                                    "multi-" + key(),
                                    "agreed",
                                    true),
                            null,
                            key());
            String buyer =
                    ok("GET", "/mp/user/profile", null, login.path("token").asString())
                            .path("userNo")
                            .asString();
            var placeBody =
                    Map.of(
                            "buyerNo",
                            buyer,
                            "cartNo",
                            cart.path("cartNo").asString(),
                            "version",
                            cart.path("version").asLong(),
                            "quoteNo",
                            quote.path("quoteNo").asString(),
                            "confirmationDigest",
                            quote.path("confirmationDigest").asString(),
                            "payMode",
                            "POSTPAY");
            String placeKey = key();
            var batch =
                    post("/biz/x/food/stores/" + STORE + "/batches", placeBody, token, placeKey);
            assertThat(batch.path("status").asString()).isEqualTo("ACCEPTED");
            assertThat(post("/biz/x/food/stores/" + STORE + "/batches", placeBody, token, placeKey))
                    .isEqualTo(batch);
            String batchNo = batch.path("batchNo").asString(),
                    order = batch.path("orderNo").asString();
            var originalItems =
                    sql.queryForList("SELECT * FROM ord_item WHERE order_no=? ORDER BY id", order);
            assertThat(originalItems).hasSize(2);
            assertThat(
                            sql.queryForList(
                                    "SELECT sku_no FROM ord_item WHERE order_no=? ORDER BY sku_no",
                                    String.class,
                                    order))
                    .containsExactlyElementsOf(skus);
            assertThat(
                            sql.queryForObject(
                                    "SELECT pay_amount FROM ord_order WHERE order_no=?",
                                    Long.class,
                                    order))
                    .isEqualTo(24140L);
            String accepted =
                    sql.queryForObject(
                            "SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND"
                                    + " event_type='DINING_ORDER_ACCEPTED'",
                            String.class,
                            batchNo);
            deliver(accepted, FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER);
            var tasks =
                    sql.queryForList(
                            "SELECT t.task_no,t.station_no,t.qty,l.sku_no,l.qty AS line_qty FROM"
                                + " fnb_kitchen_task t JOIN fnb_kitchen_ticket k ON"
                                + " k.ticket_no=t.ticket_no JOIN fnb_order_line l ON"
                                + " l.food_line_no=t.food_line_no AND l.batch_no=k.batch_no WHERE"
                                + " k.batch_no=? AND k.kind='MAKE' ORDER BY t.qty",
                            batchNo);
            assertThat(tasks).hasSize(2);
            assertThat(tasks.stream().map(t -> t.get("station_no").toString()).toList())
                    .containsExactlyInAnyOrderElementsOf(stationNos);
            var inv = new JdbcTemplate(context.getBean("invDataSource", DataSource.class));
            var acl = context.getBean(InventoryAclService.class);
            String inventoryOwner = acl.ownerIdOf("M0001");
            String inventoryLocation = acl.stockLocationOfExistingStore("M0001", STORE);
            String ingredient = acl.itemIdOf("M0001", "SK0003");
            assertThat(ingredient).isNotBlank();
            long ledgerBefore = inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class);
            var quantityBefore =
                    inv.queryForObject(
                            "SELECT COALESCE(SUM(qty_delta),0) FROM inv_ledger",
                            java.math.BigDecimal.class);
            for (var task : tasks) {
                String taskNo = task.get("task_no").toString();
                int skuIndex = skus.indexOf(task.get("sku_no").toString());
                assertThat(skuIndex).isNotNegative();
                var expectedQty = java.math.BigDecimal.valueOf(skuIndex + 1L);
                assertThat(task.get("station_no")).isEqualTo(stationNos.get(skuIndex));
                assertThat((java.math.BigDecimal) task.get("qty"))
                        .isEqualByComparingTo(expectedQty);
                assertThat((java.math.BigDecimal) task.get("line_qty"))
                        .isEqualByComparingTo(expectedQty);
                var started =
                        post(
                                "/biz/x/food/tasks/" + taskNo + ":start",
                                Map.of(
                                        "version",
                                        taskVersion(taskNo),
                                        "qty",
                                        task.get("qty"),
                                        "reason",
                                        "multi preparation"),
                                token,
                                key());
                String consumePath =
                        "/biz/x/food/production-events/"
                                + started.path("eventNo").asString()
                                + ":consume";
                String consumeKey = key();
                var consumed = post(consumePath, Map.of(), token, consumeKey);
                assertThat(consumed.path("status").asString()).isEqualTo("CONFIRMED");
                assertThat(post(consumePath, Map.of(), token, consumeKey)).isEqualTo(consumed);
                String productionEvent = started.path("eventNo").asString();
                assertThat(
                                sql.queryForObject(
                                        "SELECT task_no FROM fnb_service_event WHERE event_no=?",
                                        String.class,
                                        productionEvent))
                        .isEqualTo(taskNo);
                var receipt =
                        json.readTree(
                                inv.queryForObject(
                                        "SELECT receipt_snapshot FROM inv_production_operation"
                                                + " WHERE owner_id=? AND store_no=? AND event_no=?",
                                        String.class,
                                        inventoryOwner,
                                        STORE,
                                        productionEvent));
                assertThat(receipt.path("eventNo").asString()).isEqualTo(productionEvent);
                assertThat(receipt.path("issuedLines")).hasSize(1);
                var issued = receipt.path("issuedLines").get(0);
                assertThat(issued.path("itemRef").asString()).isEqualTo(ingredient);
                assertThat(new java.math.BigDecimal(issued.path("quantity").asString()))
                        .isEqualByComparingTo(expectedQty);
                long ledgerId = Long.parseLong(issued.path("ledgerRef").asString());
                var ledger = inv.queryForMap("SELECT * FROM inv_ledger WHERE id=?", ledgerId);
                assertThat(ledger.get("owner_id")).isEqualTo(inventoryOwner);
                assertThat(ledger.get("location_id")).isEqualTo(inventoryLocation);
                assertThat(ledger.get("item_id")).isEqualTo(ingredient);
                assertThat(ledger.get("doc_no")).isEqualTo(issued.path("documentNo").asString());
                assertThat(ledger.get("doc_no")).isEqualTo(receipt.path("documentNo").asString());
                assertThat(ledger.get("reason_code")).isEqualTo("PRODUCTION");
                assertThat((java.math.BigDecimal) ledger.get("qty_delta"))
                        .isEqualByComparingTo(expectedQty.negate());
                post(
                        "/biz/x/food/tasks/" + taskNo + ":ready",
                        Map.of(
                                "version",
                                taskVersion(taskNo),
                                "qty",
                                task.get("qty"),
                                "reason",
                                "multi ready"),
                        token,
                        key());
            }
            assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class))
                    .isEqualTo(ledgerBefore + 2);
            assertThat(
                            inv.queryForObject(
                                            "SELECT SUM(qty_delta) FROM inv_ledger",
                                            java.math.BigDecimal.class)
                                    .subtract(quantityBefore))
                    .isEqualByComparingTo("-3");
            var consumedLedger =
                    inv.queryForList(
                            "SELECT * FROM inv_ledger WHERE owner_id=? AND location_id=? ORDER BY"
                                    + " id",
                            inventoryOwner,
                            inventoryLocation);
            var consumedOnHand =
                    inv.queryForList(
                            "SELECT item_id,on_hand FROM inv_stock_balance WHERE owner_id=? AND"
                                    + " location_id=? ORDER BY item_id",
                            inventoryOwner,
                            inventoryLocation);
            var lines =
                    sql.queryForList(
                            "SELECT food_line_no,qty FROM fnb_order_line WHERE batch_no=? ORDER BY"
                                    + " sku_no",
                            batchNo);
            var quantities =
                    lines.stream()
                            .map(
                                    l ->
                                            Map.of(
                                                    "foodLineNo",
                                                    l.get("food_line_no"),
                                                    "qty",
                                                    l.get("qty")))
                            .toList();
            var handover =
                    post(
                            "/biz/x/food/batches/" + batchNo + ":handover",
                            Map.of(
                                    "kind",
                                    "DINE_IN",
                                    "lines",
                                    quantities,
                                    "reason",
                                    "multi delivery"),
                            token,
                            key());
            assertThat(handover.path("deliveryPending").asBoolean()).isFalse();
            assertThat(handover.path("coreDeliveryRef").asString()).isNotBlank();
            var returned =
                    post(
                            "/biz/x/food/batches/" + batchNo + "/business-returns",
                            Map.of(
                                    "batchVersion",
                                    sql.queryForObject(
                                            "SELECT version FROM fnb_order_batch WHERE batch_no=?",
                                            Long.class,
                                            batchNo),
                                    "lines",
                                    quantities,
                                    "reason",
                                    "multi return"),
                            token,
                            key());
            String returnNo = returned.path("returnNo").asString();
            var approval =
                    Map.of(
                            "version",
                            returned.path("version").asLong(),
                            "reason",
                            "multi approval");
            String approvalPath = "/biz/x/food/business-returns/" + returnNo + ":approve",
                    approvalKey = key();
            assertThat(post(approvalPath, approval, token, approvalKey).path("pending").asBoolean())
                    .isTrue();
            var kitchen = ok("GET", "/biz/x/food/returns/" + returnNo + "/kitchen", null, token);
            var decisions = new ArrayList<Map<String, String>>();
            for (var item : kitchen.path("items"))
                decisions.add(
                        Map.of(
                                "returnItemNo",
                                item.path("returnItemNo").asString(),
                                "disposition",
                                "KEEP"));
            assertThat(decisions).hasSize(2);
            post(
                    "/biz/x/food/returns/" + returnNo + "/kitchen",
                    Map.of(
                            "version",
                            kitchen.path("version").asLong(),
                            "decisions",
                            decisions,
                            "reason",
                            "retain consumed materials"),
                    token,
                    key());
            var approved = post(approvalPath, approval, token, approvalKey);
            assertThat(approved.path("pending").asBoolean()).isFalse();
            assertThat(post(approvalPath, approval, token, approvalKey)).isEqualTo(approved);
            assertThat(
                            sql.queryForObject(
                                    "SELECT SUM(amount_minor) FROM"
                                        + " ord_industry_receivable_adjustment WHERE order_no=?",
                                    Long.class,
                                    order))
                    .isEqualTo(-24140L);
            assertThat(
                            sql.queryForList(
                                    "SELECT * FROM ord_item WHERE order_no=? ORDER BY id", order))
                    .isEqualTo(originalItems);
            assertThat(inv.queryForObject("SELECT COUNT(*) FROM inv_ledger", Long.class))
                    .isEqualTo(ledgerBefore + 2);
            assertThat(
                            inv.queryForList(
                                    "SELECT * FROM inv_ledger WHERE owner_id=? AND location_id=?"
                                            + " ORDER BY id",
                                    inventoryOwner,
                                    inventoryLocation))
                    .isEqualTo(consumedLedger);
            assertThat(
                            inv.queryForList(
                                    "SELECT item_id,on_hand FROM inv_stock_balance WHERE owner_id=?"
                                            + " AND location_id=? ORDER BY item_id",
                                    inventoryOwner,
                                    inventoryLocation))
                    .isEqualTo(consumedOnHand);
            assertThat(
                            sql.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    order))
                    .isEqualTo("WAIT_PAY");
            assertThat(
                            sql.queryForObject(
                                    "SELECT paid_at FROM ord_order WHERE order_no=?",
                                    Object.class,
                                    order))
                    .isNull();
        }
    }

    @Order(4)
    @Test
    void realBundleAndComboModifierPreserveComponentPricesInventoryAndReturns() throws Exception {
        new FoodComplexDishNativeScenario(this).bundleAndCombo();
    }

    @Order(5)
    @Test
    void realMeasuredRecipePreservesFractionalStockAndBusinessDayReceivables() throws Exception {
        new FoodComplexDishNativeScenario(this).measuredRecipe();
    }
}
