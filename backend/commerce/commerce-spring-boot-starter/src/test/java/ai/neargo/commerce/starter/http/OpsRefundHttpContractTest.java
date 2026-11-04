package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class OpsRefundHttpContractTest {
    @Test
    void reviewBindsTrustedOperatorAndSeparateHumanDecisionWithoutProviderFacts() throws Exception {
        var seen = new ArrayList<Object>();
        var actor = new ActorContext("MAIN", SubjectKind.OPERATOR, "ops", "live");
        RefundFacade facade =
                (RefundFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {RefundFacade.class},
                                (p, m, a) -> {
                                    assertEquals("reject", m.getName());
                                    seen.addAll(Arrays.asList(a));
                                    return new RefundView(
                                            "refund:1",
                                            "order.1",
                                            AsyncStatus.FAILED,
                                            new Money("TWD", 0),
                                            new Money("TWD", 0),
                                            "DECLINED",
                                            "original",
                                            null);
                                });
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String cap) {
                        assertEquals("commerce:refund:review", cap);
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
                    public Object success(Object data) {
                        return Map.of("code", 0, "data", data);
                    }

                    public Failure failure(String code) {
                        return new Failure(400, Map.of("msg", code));
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceOpsMallHttpController(null, facade, context, envelope))
                        .build();
        String url = "/ops/biz-mall/orders/order.1/refunds/refund:1/reject?storeNo=S";
        var result =
                mvc.perform(
                                post(url)
                                        .contentType("application/json")
                                        .content(
                                                "{\"requestDigest\":\"original\",\"reviewEvidence\":\"Outside"
                                                    + " policy\"}"))
                        .andReturn()
                        .getResponse();
        assertEquals(200, result.getStatus());
        assertTrue(result.getContentAsString().contains("DECLINED"));
        assertEquals(actor, seen.getFirst());
        var command = (RefundRejectionCommand) seen.get(1);
        assertEquals(new StoreRef("MAIN", "S"), command.store());
        assertEquals("refund:1", command.refundNo());
        seen.clear();
        assertEquals(
                400,
                mvc.perform(
                                post(url)
                                        .contentType("application/json")
                                        .content(
                                                "{\"requestDigest\":\"original\",\"reviewEvidence\":\"x\",\"paidAt\":0,\"actor\":\"SYSTEM\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertTrue(seen.isEmpty());
    }
}
