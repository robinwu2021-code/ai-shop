package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

class BuyerMallHttpContractTest {
    @Test
    void trustedPathStoreExactMoneyAndNoClientCommercialFacts() throws Exception {
        var seen = new ArrayList<Object>();
        var store = new StoreRef("trusted-tenant", "S");
        var actor = new ActorContext(store.tenantNo(), SubjectKind.ACCOUNT, "USR:typed", "live");
        var money = new Money("CNY", 9007199254740993L);
        MallFacade facade =
                (MallFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {MallFacade.class},
                                (p, m, a) -> {
                                    seen.addAll(Arrays.asList(a));
                                    if (m.getName().equals("quote"))
                                        return new QuoteView(
                                                "quote:1",
                                                store,
                                                money,
                                                new Money("CNY", 0),
                                                money,
                                                List.of(),
                                                1,
                                                1,
                                                Instant.ofEpochMilli(1000));
                                    if (m.getName().equals("placeOrder"))
                                        return new OrderView(
                                                "order.1",
                                                store,
                                                OrderStatus.AWAITING_PAYMENT,
                                                money,
                                                List.of(),
                                                Instant.ofEpochMilli(1000));
                                    throw new AssertionError(m);
                                });
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String c) {
                        throw new AssertionError();
                    }

                    public Buyer buyer(String no, String cap) {
                        assertEquals("S", no);
                        assertEquals("commerce:mall:purchase", cap);
                        return new Buyer(actor, store);
                    }

                    public String payerReference(String c, String m) {
                        throw new AssertionError();
                    }
                };
        var envelope =
                new MallHttpEnvelopePort() {
                    public Object success(Object data) {
                        return Map.of("code", 0, "data", data);
                    }

                    public Failure failure(String code) {
                        return new Failure(400, Map.of("code", 10400, "msg", code));
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceBuyerMallHttpController(
                                        facade, null, null, null, context, envelope))
                        .build();
        String quote =
                "{\"lines\":[{\"skuVersionNo\":\"sku.1:current\",\"quantity\":1}],\"currency\":\"CNY\"}";
        var response =
                mvc.perform(
                                post("/biz/stores/S/mall/quotes")
                                        .contentType("application/json")
                                        .content(quote))
                        .andReturn()
                        .getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"minor\":\"9007199254740993\""));
        assertEquals(actor, seen.getFirst());
        assertEquals(store, ((QuoteCommand) seen.get(1)).store());
        seen.clear();
        assertEquals(
                400,
                mvc.perform(
                                post("/biz/stores/S/mall/quotes")
                                        .contentType("application/json")
                                        .content(
                                                quote.substring(0, quote.length() - 1)
                                                        + ",\"store\":{\"tenantNo\":\"evil\",\"storeNo\":\"other\"}}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertTrue(seen.isEmpty());
        assertEquals(
                200,
                mvc.perform(
                                post("/biz/stores/S/mall/orders")
                                        .contentType("application/json")
                                        .content(
                                                "{\"quoteNo\":\"quote:1\",\"idempotencyKey\":\"place.1\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(store, ((PlaceOrderCommand) seen.get(1)).expectedStore());
        assertEquals(
                400,
                mvc.perform(
                                post("/biz/stores/S/mall/orders/order.1/payments")
                                        .contentType("application/json")
                                        .content(
                                                "{\"channel\":\"WECHAT\",\"payMethod\":\"JSAPI\",\"paid\":true}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }
}
