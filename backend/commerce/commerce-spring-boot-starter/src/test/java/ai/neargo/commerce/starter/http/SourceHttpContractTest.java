package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class SourceHttpContractTest {
    @Test
    void sourceGrantsUseTrustedOpsAndCannotAcceptPriceOrPartialPlanClaims() throws Exception {
        var seen = new ArrayList<Object>();
        var actor = new ActorContext("MAIN", SubjectKind.OPERATOR, "ops", "live");
        SourceManagementFacade facade =
                (SourceManagementFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {SourceManagementFacade.class},
                                (p, m, a) -> {
                                    assertEquals("grant", m.getName());
                                    seen.addAll(Arrays.asList(a));
                                    return new SubscriptionView(
                                            new StoreRef("MAIN", "S"), 2, List.of(), List.of());
                                });
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String cap) {
                        assertEquals("commerce:subscription:grant", cap);
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
                                new CommerceSourceHttpController(facade, context, envelope))
                        .build();
        String url = "/ops/stores/S/subscription/grants";
        String body =
                "{\"operationNo\":\"grant.1\",\"entitlementCode\":\"END:POS\",\"durationDays\":2,\"reason\":\"Support\"}";
        assertEquals(
                200,
                mvc.perform(post(url).contentType("application/json").content(body))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(actor, seen.getFirst());
        var command = (GrantCommand) seen.get(1);
        assertEquals(new StoreRef("MAIN", "S"), command.store());
        assertTrue(command.planVersionNo().isEmpty());
        seen.clear();
        for (String forged :
                List.of(
                        "\"cashAmount\":123",
                        "\"planVersionNo\":\"paid-plan\"",
                        "\"actor\":\"SYSTEM\""))
            assertEquals(
                    400,
                    mvc.perform(
                                    post(url)
                                            .contentType("application/json")
                                            .content(
                                                    body.substring(0, body.length() - 1)
                                                            + ","
                                                            + forged
                                                            + "}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        assertTrue(seen.isEmpty());
    }
}
