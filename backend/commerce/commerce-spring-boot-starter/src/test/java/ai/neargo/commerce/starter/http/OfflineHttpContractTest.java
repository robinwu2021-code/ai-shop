package ai.neargo.commerce.starter.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade.*;
import ai.neargo.commerce.api.port.PrivateEvidencePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

/** Offline HTTP: identity and store from the host context, exact money, codes for refusals. */
class OfflineHttpContractTest {
    final StoreRef store = new StoreRef("MAIN", "S");
    final ActorContext buyer = new ActorContext("MAIN", SubjectKind.ACCOUNT, "USR:buyer", "live");
    final ActorContext ops = new ActorContext("MAIN", SubjectKind.OPERATOR, "ops", "live");
    final List<Object[]> calls = new ArrayList<>();
    final List<String> methods = new ArrayList<>();
    final List<String> capabilities = new ArrayList<>();
    String failWith;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var offline =
                (OfflinePurchaseFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {OfflinePurchaseFacade.class},
                                (p, m, a) -> {
                                    methods.add(m.getName());
                                    calls.add(a);
                                    if (failWith != null) throw new MallException(failWith);
                                    return switch (m.getName()) {
                                        case "submit" -> new ClaimView("claim", "SUBMITTED",
                                                new Money("TWD", 69900), Instant.parse("2026-11-04T01:00:00Z"),
                                                Instant.parse("2026-11-04T02:00:00Z"));
                                        case "confirm" -> new ActionView("act", "APPLIED", "pay-1");
                                        case "issue", "instructions" -> new InstructionsView("order",
                                                new Money("TWD", 9007199254740993L),
                                                Instant.parse("2026-11-05T00:00:00Z"), "profile", 2,
                                                Map.of("bankCode", "TESTBANK"));
                                        case "reserveEvidence" -> new EvidenceTicket(
                                                "E1/S/mallpay/202611/k.jpg", (String) a[2]);
                                        case "evidence" -> "/private/signed";
                                        default -> null;
                                    };
                                });
        var context =
                new MallHttpContextPort() {
                    public ActorContext operator(String c) {
                        capabilities.add(c);
                        return ops;
                    }

                    public Buyer buyer(String no, String c) {
                        assertEquals("S", no);
                        capabilities.add(c);
                        return new Buyer(buyer, store);
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
                        int status = code.startsWith("INVALID_") ? 400 : 409;
                        return new Failure(status, Map.of("code", 10000 + status, "msg", code));
                    }
                };
        var mall =
                (MallFacade)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {MallFacade.class},
                                (p, m, a) -> {
                                    methods.add("mall." + m.getName());
                                    calls.add(a);
                                    return null;
                                });
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new CommerceOfflineHttpController(offline, mall, context, envelope))
                        .build();
    }

    @Test
    void merchantClaimUsesContextStoreExactMoneyAndRejectsForgedFields() throws Exception {
        String claim =
                "{\"claimNo\":\"claim\",\"declaredAmount\":{\"currency\":\"TWD\",\"minor\":\"69900\"},"
                        + "\"declaredPaidAt\":\"2026-11-04T01:00:00Z\",\"bankReference\":\"ref\","
                        + "\"evidenceKeys\":[\"E1/S/mallpay/202611/k.jpg\"]}";
        var r =
                mvc.perform(post("/biz/stores/S/mall/offline/orders/order/claims")
                                .contentType("application/json").content(claim))
                        .andReturn().getResponse();
        assertEquals(200, r.getStatus());
        assertTrue(r.getContentAsString().contains("\"minor\":\"69900\""));
        assertEquals(buyer, calls.getFirst()[0]);
        var command = (ClaimCommand) calls.getFirst()[1];
        assertEquals(new OrderQuery(store, "order"), command.order());
        assertEquals(69900, command.declaredAmount().minor());
        assertEquals(List.of("commerce:mall:purchase"), capabilities);
        calls.clear();
        String forged = claim.substring(0, claim.length() - 1) + ",\"paid\":true}";
        assertEquals(400, mvc.perform(post("/biz/stores/S/mall/offline/orders/order/claims")
                        .contentType("application/json").content(forged)).andReturn().getResponse().getStatus());
        String fractional = claim.replace("\"69900\"", "699.5");
        assertEquals(400, mvc.perform(post("/biz/stores/S/mall/offline/orders/order/claims")
                        .contentType("application/json").content(fractional)).andReturn().getResponse().getStatus());
        assertTrue(calls.isEmpty());
    }

    @Test
    void evidenceUploadIsBinaryForAReservedKeyAndTraversalIsRefused() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        var r =
                mvc.perform(post("/biz/stores/S/mall/offline/orders/order/evidence/content")
                                .param("assetKey", "E1/S/mallpay/202611/k.jpg")
                                .contentType("image/jpeg").content(jpeg))
                        .andReturn().getResponse();
        assertEquals(200, r.getStatus());
        assertEquals("upload", methods.getFirst());
        var upload = (PrivateEvidencePort.Upload) calls.getFirst()[3];
        assertEquals("image/jpeg", upload.contentType());
        assertEquals(4, upload.size());
        assertArrayEquals(jpeg, upload.content().get().readAllBytes());
        calls.clear();
        assertEquals(400, mvc.perform(post("/biz/stores/S/mall/offline/orders/order/evidence/content")
                        .param("assetKey", "E1/../other/k.jpg").contentType("image/jpeg").content(jpeg))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(post("/biz/stores/S/mall/offline/orders/order/evidence/content")
                        .param("assetKey", "https://evil/x.jpg").contentType("image/jpeg").content(jpeg))
                .andReturn().getResponse().getStatus());
        assertTrue(calls.isEmpty());
        var reserved =
                mvc.perform(post("/biz/stores/S/mall/offline/orders/order/evidence")
                                .contentType("application/json").content("{}"))
                        .andReturn().getResponse();
        assertEquals(200, reserved.getStatus());
        assertEquals("BUYER_RECEIPT", calls.getFirst()[2]);
    }

    @Test
    void operatorActionsUseSeparateCapabilitiesAndExplicitStore() throws Exception {
        String confirm =
                "{\"actionNo\":\"act\",\"claimNo\":null,\"actualAmount\":{\"currency\":\"TWD\",\"minor\":\"69900\"},"
                        + "\"actualPaidAt\":\"2026-11-04T01:00:00Z\",\"actualReceiver\":\"platform\","
                        + "\"bankReference\":\"bank\",\"evidenceKey\":\"E1/S/mallpay/202611/p.jpg\","
                        + "\"historical\":false,\"reason\":\"checked\"}";
        assertEquals(200, mvc.perform(post("/ops/biz-mall/offline/orders/order/confirmations")
                        .param("storeNo", "S").contentType("application/json").content(confirm))
                .andReturn().getResponse().getStatus());
        var review = (ReviewCommand) calls.getLast()[1];
        assertEquals(new OrderQuery(store, "order"), review.order());
        assertEquals(ops, calls.getLast()[0]);
        String payout =
                "{\"actualAmount\":{\"currency\":\"TWD\",\"minor\":\"4000\"},\"paidOutAt\":\"2026-11-04T03:00:00Z\","
                        + "\"payeeReference\":\"payee\",\"bankReference\":\"out\",\"evidenceKey\":\"E1/S/mallpay/202611/r.jpg\"}";
        assertEquals(200, mvc.perform(post("/ops/biz-mall/offline/orders/order/refunds/refund-1/payouts")
                        .param("storeNo", "S").contentType("application/json").content(payout))
                .andReturn().getResponse().getStatus());
        assertEquals("refund-1", ((RefundPayoutCommand) calls.getLast()[1]).refundNo());
        assertEquals(200, mvc.perform(post("/ops/biz-mall/offline/orders/order/voids")
                        .param("storeNo", "S").contentType("application/json")
                        .content("{\"actionNo\":\"v\",\"reconciliationReference\":\"r\",\"reason\":\"x\","
                                + "\"evidenceKey\":\"E1/S/mallpay/202611/v.jpg\",\"unmatchedDispositions\":{}}"))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(post("/ops/biz-mall/offline/orders/order/claims/claim/decline")
                        .param("storeNo", "S").contentType("application/json").content("{\"reason\":\"blurry\"}"))
                .andReturn().getResponse().getStatus());
        assertEquals(
                List.of(
                        "commerce:mall:offline:confirm",
                        "commerce:mall:offline:refund:confirm",
                        "commerce:mall:offline:void",
                        "commerce:mall:offline:review"),
                capabilities);
        assertEquals(400, mvc.perform(post("/ops/biz-mall/offline/orders/order/confirmations")
                        .param("storeNo", "S/../T").contentType("application/json").content(confirm))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void facadeRefusalsBecomeEnvelopeCodes() throws Exception {
        failWith = "OFFLINE_INSTRUCTIONS_NOT_ISSUED";
        var r = mvc.perform(get("/biz/stores/S/mall/offline/orders/order/instructions")).andReturn().getResponse();
        assertEquals(409, r.getStatus());
        assertTrue(r.getContentAsString().contains("OFFLINE_INSTRUCTIONS_NOT_ISSUED"));
    }
}
