package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Exports actual MVC responses for the companion Draft 2020-12/OpenAPI 3.1 validation gate. */
class MallOpenApiResponseTest {
    @Test
    void actualNullableResponsesRetainExactMoneyAndWireNulls() throws Exception {
        var store = new StoreRef("MAIN", "S");
        var amount = new Money("CNY", 9007199254740993L);
        var zero = new Money("CNY", 0);
        var at = Instant.parse("2026-11-04T00:00:00Z");
        var detail =
                new MallOrderDetailView(
                        new MallOrderDetailView.Summary(
                                new OrderView(
                                        "order",
                                        store,
                                        OrderStatus.AWAITING_PAYMENT,
                                        amount,
                                        List.of(),
                                        at),
                                "PAYING",
                                "PENDING",
                                "NONE",
                                "套餐",
                                1,
                                null),
                        new QuoteView("quote", store, amount, zero, amount, List.of(), 1, 1, at),
                        null,
                        List.of(
                                new MallOrderDetailView.PaymentAttempt(
                                        "attempt",
                                        "WECHAT",
                                        "JSAPI",
                                        amount,
                                        AsyncStatus.PENDING,
                                        null,
                                        null,
                                        null)),
                        List.of(
                                new MallOrderDetailView.Shipment(
                                        "ship",
                                        "component",
                                        "carrier",
                                        "tracking",
                                        1,
                                        AsyncStatus.PENDING,
                                        null)),
                        List.of(
                                new RefundView(
                                        "refund",
                                        "order",
                                        AsyncStatus.PENDING,
                                        amount,
                                        zero,
                                        "REQUESTED",
                                        "digest",
                                        null)),
                        List.of(new MallOrderDetailView.Line("product", "sku", "sku-1", "套餐", 1)));
        var queries =
                (MallQueryFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {MallQueryFacade.class},
                                (p, m, a) -> detail);
        var catalog =
                (CatalogFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {CatalogFacade.class},
                                (p, m, a) ->
                                        "categories".equals(m.getName())
                                                ? List.of(
                                                        new CategoryView("hw", null, 1, Map.of("zh-TW", "硬體"), null, 1, "ENABLED", 1),
                                                        new CategoryView("pos", "hw", 2, Map.of("en", "POS"), "https://cdn/x.png", 0, "DISABLED", 3))
                                                : new DraftSaveResult("retail", null, 1));
        var sku =
                new SkuDraft("product", "sku", "sku-1", "套餐", amount, amount, List.of("retail"), "{}",
                        List.of(), 1, 1, "publish");
        var online = new ai.neargo.commerce.api.port.CheckoutReadinessPort.Readiness(
                false, List.of("PAYER_BINDING_UNPROVEN"), List.of());
        var offlineReady = new ai.neargo.commerce.api.port.CheckoutReadinessPort.Readiness(
                true, List.of(), List.of(new ai.neargo.commerce.api.port.CheckoutReadinessPort.PaymentOption(
                        "OFFLINE", "BANK_TRANSFER", "CNY", 9007199254740991L)));
        StorefrontFacade storefront =
                (a, s) -> new StorefrontView(s, "retail", "CNY", 1, true, List.of(new StorefrontView.Product(
                        new ProductDraft("product", "cat", ProductKind.BUNDLE, "套餐", "d", List.of("retail"), 0, 1, "publish"),
                        List.of(new StorefrontView.Sku(sku, online, offlineReady), new StorefrontView.Sku(sku, online)))),
                        List.of(new StorefrontView.Category("cat", null, 1, Map.of("en", "Plans"), null, 0)));
        var actor = new ActorContext("MAIN", SubjectKind.ACCOUNT, "buyer", "live");
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String c) {
                        return new ActorContext("MAIN", SubjectKind.OPERATOR, "ops", "live");
                    }

                    public Buyer buyer(String s, String c) {
                        return new Buyer(actor, store);
                    }

                    public String payerReference(String c, String m) {
                        throw new AssertionError();
                    }
                };
        var envelope =
                new MallHttpEnvelopePort() {
                    public Object success(Object d) {
                        return Map.of("code", 0, "msg", "success", "data", d);
                    }

                    public Failure failure(String c) {
                        return new Failure(400, Map.of("error", c));
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceBuyerMallHttpController(
                                        null, queries, null, null, context, envelope),
                                new CommerceCatalogHttpController(catalog, context, envelope),
                                new CommerceMallProductHttpController(catalog, context, envelope),
                                new CommerceStorefrontHttpController(storefront, context, envelope))
                        .build();
        var order = mvc.perform(get("/biz/stores/S/mall/orders/order")).andReturn().getResponse();
        var save =
                mvc.perform(
                                post("/ops/business-types")
                                        .contentType("application/json")
                                        .content(
                                                "{\"typeCode\":\"retail\",\"name\":\"Retail\",\"sortOrder\":0,\"enabled\":false,\"expectedVersion\":0,\"reason\":\"create\"}"))
                        .andReturn()
                        .getResponse();
        var products = mvc.perform(get("/biz/stores/S/mall/products")).andReturn().getResponse();
        var categories = mvc.perform(get("/ops/biz-mall/categories")).andReturn().getResponse();
        assertEquals(200, categories.getStatus());
        var savedCategory =
                mvc.perform(
                                put("/ops/biz-mall/categories/pos")
                                        .contentType("application/json")
                                        .content(
                                                "{\"parentCode\":\"hw\",\"names\":{\"en\":\"POS\"},\"icon\":null,\"sortOrder\":0,\"enabled\":true,\"expectedVersion\":3,\"reason\":\"edit\"}"))
                        .andReturn()
                        .getResponse();
        assertEquals(200, savedCategory.getStatus());
        assertEquals(200, products.getStatus());
        assertTrue(products.getContentAsString().contains("\"offlineReadiness\":null"));
        assertEquals(200, order.getStatus());
        assertEquals(200, save.getStatus());
        assertTrue(order.getContentAsString().contains("\"shippingAddress\":null"));
        assertTrue(order.getContentAsString().contains("\"minor\":\"9007199254740993\""));
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var offline = offlineResponses(context, envelope, store, at);
        var fixtures = new LinkedHashMap<String, Object>();
        fixtures.put("MallOrderDetailView", mapper.readTree(order.getContentAsString()).get("data"));
        fixtures.put("DraftSaveResult", mapper.readTree(save.getContentAsString()).get("data"));
        fixtures.put("StorefrontView", mapper.readTree(products.getContentAsString()).get("data"));
        fixtures.put("CategoryView", mapper.readTree(categories.getContentAsString()).get("data").get(0));
        assertTrue(categories.getContentAsString().contains("\"parentCode\":null"));
        for (var e : offline.entrySet())
            fixtures.put(e.getKey(), mapper.readTree(e.getValue()).get("data"));
        assertEquals("null", mapper.readTree(offline.get("OfflineActionView")).get("data").get("paymentNo").toString());
        Path target = Path.of("target/mall-openapi-responses.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, mapper.writeValueAsString(fixtures));
    }

    /** Actual offline controller responses for the same schema gate. */
    static Map<String, String> offlineResponses(
            MallHttpContextPort context, MallHttpEnvelopePort envelope, StoreRef store, Instant at)
            throws Exception {
        var money = new Money("TWD", 69900);
        var terms =
                new ai.neargo.commerce.api.offline.OfflineCollectionTerms(
                        "profile", 2, "platform", "TWD", "account-key", "w1", 86400000,
                        Map.of("bankCode", "TESTBANK"));
        var claim =
                new ai.neargo.commerce.api.offline.OfflinePurchaseFacade.ClaimView(
                        "claim", "MISMATCHED", money, at, at, new Money("TWD", 69800), "short paid");
        var facade =
                (ai.neargo.commerce.api.offline.OfflinePurchaseFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                MallOpenApiResponseTest.class.getClassLoader(),
                                new Class[] {ai.neargo.commerce.api.offline.OfflinePurchaseFacade.class},
                                (p, m, a) ->
                                        switch (m.getName()) {
                                            case "instructions" ->
                                                    new ai.neargo.commerce.api.offline.OfflinePurchaseFacade
                                                            .InstructionsView("order", money, at, "profile", 2,
                                                            Map.of("bankCode", "TESTBANK", "transferRemark", "order"));
                                            case "claims" -> List.of(claim);
                                            case "pendingClaims" ->
                                                    List.of(new ai.neargo.commerce.api.offline.OfflinePurchaseFacade
                                                            .PendingClaim(store, "order", claim));
                                            case "voidOrder" ->
                                                    new ai.neargo.commerce.api.offline.OfflinePurchaseFacade
                                                            .ActionView("void", "ACCEPTED", null);
                                            case "profile" -> Optional.of(terms);
                                            default -> throw new AssertionError(m);
                                        });
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceOfflineBuyerHttpController(facade, context, envelope),
                                new CommerceOfflineOpsHttpController(
                                        facade,
                                        (MallFacade)
                                                java.lang.reflect.Proxy.newProxyInstance(
                                                        MallOpenApiResponseTest.class.getClassLoader(),
                                                        new Class[] {MallFacade.class},
                                                        (p, m, a) -> null),
                                        context,
                                        envelope))
                        .build();
        var out = new LinkedHashMap<String, String>();
        out.put("OfflineInstructionsView",
                mvc.perform(get("/biz/stores/S/mall/offline/orders/order/instructions")).andReturn().getResponse().getContentAsString());
        out.put("OfflineClaimView", wrapFirst(
                mvc.perform(get("/biz/stores/S/mall/offline/orders/order/claims")).andReturn().getResponse().getContentAsString()));
        out.put("OfflinePendingClaim", wrapFirst(
                mvc.perform(get("/ops/biz-mall/offline/claims")).andReturn().getResponse().getContentAsString()));
        out.put("OfflineActionView",
                mvc.perform(post("/ops/biz-mall/offline/orders/order/voids").param("storeNo", "S")
                                .contentType("application/json")
                                .content("{\"actionNo\":\"void\",\"reconciliationReference\":\"r\",\"reason\":\"x\","
                                        + "\"evidenceKey\":\"E/S/mallpay/202611/k.jpg\",\"unmatchedDispositions\":{}}"))
                        .andReturn().getResponse().getContentAsString());
        out.put("OfflineCollectionTerms",
                mvc.perform(get("/ops/biz-mall/offline/profile").param("currency", "TWD")).andReturn().getResponse().getContentAsString());
        for (var body : out.values()) assertTrue(body.contains("\"code\":0"), body);
        return out;
    }

    /** Array endpoints: validate the element schema against the first real item. */
    static String wrapFirst(String envelope) throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(envelope);
        tree.set("data", tree.get("data").get(0));
        return mapper.writeValueAsString(tree);
    }
}
