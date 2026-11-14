package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class MallCoordinationHttpTest {
    @Test
    void refreshAuthorizesStoreReadBeforeTrustedRecoveryAndAcceptsNoMoneyFacts() throws Exception {
        var calls = new ArrayList<String>();
        var store = new StoreRef("MAIN", "S");
        var actor = new ActorContext("MAIN", SubjectKind.ACCOUNT, "USR:buyer", "live");
        var queries =
                (MallQueryFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {MallQueryFacade.class},
                                (p, m, a) -> {
                                    calls.add("read");
                                    assertEquals(actor, a[0]);
                                    assertEquals(store, ((OrderQuery) a[1]).store());
                                    return new MallOrderDetailView(
                                            null, null, null, List.of(), List.of(), List.of());
                                });
        PaymentFacade payments =
                new PaymentFacade() {
                    public ai.neargo.commerce.api.port.PaymentOutcome startPayment(
                            ActorContext a, StartPaymentCommand c) {
                        throw new AssertionError();
                    }

                    public FulfillmentResult recover(FulfillmentQuery q) {
                        throw new AssertionError();
                    }

                    public FulfillmentResult refresh(FulfillmentQuery q) {
                        calls.add("refresh");
                        assertEquals("MAIN", q.tenantNo());
                        return null;
                    }
                };
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String c) {
                        throw new AssertionError();
                    }

                    public Buyer buyer(String no, String cap) {
                        assertEquals("commerce:mall:read", cap);
                        return new Buyer(actor, store);
                    }

                    public String payerReference(String c, String m) {
                        throw new AssertionError();
                    }
                };
        var envelope =
                new MallHttpEnvelopePort() {
                    public Object success(Object d) {
                        return Map.of("data", d);
                    }

                    public Failure failure(String c) {
                        return new Failure(400, Map.of("error", c));
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceBuyerMallHttpController(
                                        null, queries, payments, null, context, envelope))
                        .build();
        String path = "/biz/stores/S/mall/orders/order:1/payments/refresh";
        assertEquals(
                200,
                mvc.perform(post(path).contentType("application/json").content("{}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(List.of("read", "refresh", "read"), calls);
        calls.clear();
        assertEquals(
                400,
                mvc.perform(post(path).contentType("application/json").content("{\"paid\":true}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertTrue(calls.isEmpty());
    }

    @Test
    void shipmentRequiresNamedOpsCapabilityAndBindsActualOperator() throws Exception {
        var calls = new ArrayList<String>();
        var actor = new ActorContext("MAIN", SubjectKind.OPERATOR, "ops-shipping", "live");
        var queries =
                (MallQueryFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {MallQueryFacade.class},
                                (p, m, a) -> {
                                    calls.add("read");
                                    assertEquals(actor, a[0]);
                                    assertEquals(
                                            new StoreRef("MAIN", "S"), ((OrderQuery) a[1]).store());
                                    return new MallOrderDetailView(
                                            null, null, null, List.of(), List.of(), List.of());
                                });
        var fulfillment =
                (FulfillmentFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {FulfillmentFacade.class},
                                (p, m, a) -> {
                                    calls.add("ship");
                                    var command = (HardwareShipmentCommand) a[0];
                                    assertEquals("ops-shipping", command.operatorNo());
                                    assertEquals("MAIN", command.tenantNo());
                                    assertEquals("part:1", command.orderComponentNo());
                                    return null;
                                });
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String cap) {
                        assertEquals("commerce:mall:shipment:write", cap);
                        return actor;
                    }

                    public Buyer buyer(String s, String c) {
                        throw new AssertionError();
                    }

                    public String payerReference(String c, String m) {
                        throw new AssertionError();
                    }
                };
        var envelope =
                new MallHttpEnvelopePort() {
                    public Object success(Object d) {
                        return Map.of("data", d);
                    }

                    public Failure failure(String c) {
                        return new Failure(400, Map.of("error", c));
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceOpsMallHttpController(
                                        queries, null, fulfillment, context, envelope))
                        .build();
        String body =
                "{\"orderComponentNo\":\"part:1\",\"operationNo\":\"ship:1\",\"carrierCode\":\"carrier\",\"trackingNo\":\"track\",\"quantity\":1}";
        String path = "/ops/biz-mall/orders/order:1/shipments?storeNo=S";
        assertEquals(
                200,
                mvc.perform(post(path).contentType("application/json").content(body))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(List.of("read", "ship", "read"), calls);
        calls.clear();
        assertEquals(
                400,
                mvc.perform(
                                post(path)
                                        .contentType("application/json")
                                        .content(
                                                body.substring(0, body.length() - 1)
                                                        + ",\"operatorNo\":\"SYSTEM\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertTrue(calls.isEmpty());
    }
}
