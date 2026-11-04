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
                                "NONE"),
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
                                        null)));
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
                                (p, m, a) -> new DraftSaveResult("retail", null, 1));
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
                                        null, null, queries, null, null, context, envelope),
                                new CommerceCatalogHttpController(catalog, context, envelope))
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
        assertEquals(200, order.getStatus());
        assertEquals(200, save.getStatus());
        assertTrue(order.getContentAsString().contains("\"shippingAddress\":null"));
        assertTrue(order.getContentAsString().contains("\"minor\":\"9007199254740993\""));
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var fixtures =
                Map.of(
                        "MallOrderDetailView",
                        mapper.readTree(order.getContentAsString()).get("data"),
                        "DraftSaveResult",
                        mapper.readTree(save.getContentAsString()).get("data"));
        Path target = Path.of("target/mall-openapi-responses.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, mapper.writeValueAsString(fixtures));
    }
}
