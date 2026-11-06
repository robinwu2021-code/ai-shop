package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TDD-运营端申请支付服务 A2 A3 A4。 */
class PayServiceApplyRequestsTest {

    static final StoreSyncProperties PROPS = new StoreSyncProperties(true, "http://127.0.0.1:18093", null, "cred", null, null,
            null, null, null);
    static final StoreSnapshotReader.Entity ENTITY =
            new StoreSnapshotReader.Entity("M261106BF7GK8052W", "台北早餐", "TW", 1_762_400_000_000L);

    static StoreSnapshotReader.Snapshot store(long id, String no, boolean deleted) {
        return new StoreSnapshotReader.Snapshot(id, no, "M261106BF7GK8052W", "台北早餐", null, "TW", "台北早餐", null, null,
                "07:00-13:00", "台北市中山區", "1樓", "ACTIVE", deleted, 1_793_952_472_000L);
    }

    static final PayServiceApplyRequests.Form FORM =
            new PayServiceApplyRequests.Form(" 創勢科技股份有限公司 ", "507656342", null, null, null);

    @Test
    @DisplayName("★★★ 门店的 externalId / revision / profileHash 与门店同步推给 near-pay 的完全一致（不一致就关联不上投影）")
    void storeRefsMatchStoreSync() {
        var s = store(9, "ST261106T585CY14HG", false);
        var body = PayServiceApplyRequests.build(ENTITY, List.of(s, store(10, "ST-GONE", true)), FORM, "ST-ADMIN", PROPS);
        Map<String, Object> sync = NearPayStoreProfile.request(s, PROPS);
        assertThat(body.stores()).hasSize(1);
        var in = body.stores().getFirst();
        assertThat(in.externalId()).isEqualTo(sync.get("storeRef")).isEqualTo("9");
        assertThat(in.revision()).isEqualTo(((Number) sync.get("revision")).longValue());
        assertThat(in.profileHash()).isEqualTo(sync.get("profileHash")).matches("[0-9a-f]{64}");
        assertThat(in.storeNo()).isEqualTo("ST261106T585CY14HG");
        assertThat(body.sourceEntityRef()).isEqualTo("M261106BF7GK8052W");
        assertThat(body.sourceScope()).isEqualTo("MAIN");
        assertThat(body.capabilityType()).isEqualTo("FIAT");
        assertThat(body.profile().purpose()).isEqualTo("FIAT");
        assertThat(body.profile().legalName()).isEqualTo("創勢科技股份有限公司");
        assertThat(body.actorRef().realm()).isEqualTo("OPERATOR");
        assertThat(body.actorRef().subject()).isEqualTo("ST-ADMIN");
        assertThat(body.entityRevision()).isEqualTo(ENTITY.revision()).isEqualTo(body.profileRevision());
    }

    @Test
    @DisplayName("★★ 幂等：同主体同版本同资料 → 同一事件号；资料一变 → 新事件号")
    void eventIdStableUntilProfileChanges() {
        var a = PayServiceApplyRequests.build(ENTITY, List.of(), FORM, "ST-ADMIN", PROPS).sourceEventId();
        var b = PayServiceApplyRequests.build(ENTITY, List.of(), FORM, "ST-OTHER", PROPS).sourceEventId();
        var c = PayServiceApplyRequests.build(ENTITY, List.of(),
                new PayServiceApplyRequests.Form("創勢科技股份有限公司", "507656343", null, null, null), "ST-ADMIN", PROPS).sourceEventId();
        assertThat(a).isEqualTo(b).startsWith("ais-apply-M261106BF7GK8052W-1762400000000-").hasSizeLessThanOrEqualTo(64);
        assertThat(c).isNotEqualTo(a);
    }

    @Test
    @DisplayName("★★ 法定名称、统一编号、运营身份必填；主体不存在报不存在")
    void requiredFields() {
        assertThatThrownBy(() -> PayServiceApplyRequests.build(ENTITY, List.of(),
                new PayServiceApplyRequests.Form(" ", "507656342", null, null, null), "ST-ADMIN", PROPS)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PayServiceApplyRequests.build(ENTITY, List.of(),
                new PayServiceApplyRequests.Form("x", null, null, null, null), "ST-ADMIN", PROPS)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PayServiceApplyRequests.build(ENTITY, List.of(), FORM, null, PROPS)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PayServiceApplyRequests.build(null, List.of(), FORM, "ST-ADMIN", PROPS)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 真发请求：经 PAY 客户端打到 near-pay 的 source-links/apply，带调用方头")
    void sendsThroughPayClientWithCallerHeaders() throws Exception {
        var seen = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.HttpExchange>();
        var path = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.set(ex);
            path.set(ex.getRequestURI().getPath());
            byte[] b = "{\"merchantRef\":\"PM1\",\"storeLinks\":[],\"applicationRef\":\"APP1\",\"status\":\"VERIFY_PENDING\"}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            var env = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("shop.pay.internal-caller", "AI_SHOP_UME")
                    .withProperty("shop.pay.internal-caller-credentials.AI_SHOP_UME", "cred-ume");
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                    Map.of("payCaller", new PayClientConfig().payCallerHeaders(env)));
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var http = new ai.neargo.shop.svc.InternalHttp(svc -> java.util.Optional.of(base), "tok",
                    beans.getBeanProvider(ai.neargo.shop.svc.InternalServiceInterceptor.class));
            var result = new PayClientConfig().paySourceLinkApi(http)
                    .apply(PayServiceApplyRequests.build(ENTITY, List.of(), FORM, "ST-ADMIN", PROPS));
            assertThat(path.get()).isEqualTo("/internal/pay/v2/source-links/apply");
            assertThat(seen.get().getRequestHeaders().getFirst("X-Internal-Caller")).isEqualTo("AI_SHOP_UME");
            assertThat(seen.get().getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("tok");
            assertThat(result.merchantRef()).isEqualTo("PM1");
            assertThat(result.status()).isEqualTo("VERIFY_PENDING");
        } finally {
            server.stop(0);
        }
    }
}
