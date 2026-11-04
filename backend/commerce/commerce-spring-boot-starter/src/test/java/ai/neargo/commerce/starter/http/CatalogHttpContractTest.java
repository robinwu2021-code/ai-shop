package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class CatalogHttpContractTest {
    @Test
    void realMvcUsesOnlyTrustedOpsAndRejectsForgedActorFields() throws Exception {
        var seen = new ArrayList<Object>();
        var ops = new ActorContext("trusted-tenant", SubjectKind.OPERATOR, "operator", "live");
        CatalogFacade facade =
                (CatalogFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {CatalogFacade.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("saveBusinessType")) {
                                        seen.addAll(Arrays.asList(a));
                                        return new DraftSaveResult("retail.cn:1", null, 1);
                                    }
                                    throw new UnsupportedOperationException();
                                });
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String c) {
                        assertEquals("commerce:catalog:edit", c);
                        return ops;
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
                        return Map.of("code", 0, "msg", "success", "data", data);
                    }

                    public Failure failure(String code) {
                        return new Failure(400, Map.of("code", 10400, "msg", code));
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceCatalogHttpController(facade, context, envelope))
                        .build();
        String body =
                "{\"typeCode\":\"retail.cn:1\",\"name\":\"Retail\",\"sortOrder\":1,\"enabled\":false,\"expectedVersion\":0,\"reason\":\"create\"}";
        var result =
                mvc.perform(
                                post("/ops/business-types")
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn()
                        .getResponse();
        assertEquals(200, result.getStatus());
        assertTrue(result.getContentAsString().contains("retail.cn:1"));
        assertEquals(ops, seen.getFirst());
        assertEquals("retail.cn:1", ((BusinessTypeDraft) seen.get(1)).typeCode());
        seen.clear();
        assertEquals(
                400,
                mvc.perform(
                                post("/ops/business-types")
                                        .contentType("application/json")
                                        .content(
                                                body.substring(0, body.length() - 1)
                                                        + ",\"tenantNo\":\"evil\",\"actor\":\"SYSTEM\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertTrue(seen.isEmpty());
    }
}
