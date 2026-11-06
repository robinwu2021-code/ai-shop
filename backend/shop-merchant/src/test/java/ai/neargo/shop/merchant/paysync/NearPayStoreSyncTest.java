package ai.neargo.shop.merchant.paysync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.SysOutbox;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NearPayStoreSyncTest {

    static final StoreSyncProperties PROPS = new StoreSyncProperties(true, "http://127.0.0.1:18093", null, "cred", null, null,
            "https://img.example", null, Duration.ofSeconds(2));

    static StoreSnapshotReader.Snapshot snap(String logo, String banner, String slug, String status, boolean deleted) {
        return new StoreSnapshotReader.Snapshot(41, "ST41", "E41", "ume 股份有限公司", logo, "TW", "ume早餐店", slug, banner,
                "07:00-14:00", "台北市中山區", "1樓", status, deleted, 1_762_300_000_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestMapsSnapshotAndHashMatchesNearPayCanonicalRule() throws Exception {
        var body = NearPayStoreProfile.request(snap("/uploads/logo.png", null, "UME-Breakfast", "ACTIVE", false), PROPS);
        var profile = (Map<String, Object>) body.get("profile");
        assertThat(profile).containsEntry("name", "ume早餐店").containsEntry("entityName", "ume 股份有限公司")
                .containsEntry("address", "台北市中山區 1樓").containsEntry("openHours", "07:00-14:00")
                .containsEntry("logoUrl", "https://img.example/uploads/logo.png").containsEntry("storeCode", "ume-breakfast")
                .containsEntry("status", "ACTIVE").containsEntry("market", "TW")
                // 资金路径：near-pay 解析门店要求 DIRECT / AGGREGATED，空就拒绝收款（PAY_FORBIDDEN）。目前默认直付
                .containsEntry("fundsPath", "DIRECT");
        assertThat(body).containsEntry("storeRef", "41").containsEntry("storeNo", "ST41").containsEntry("entityRef", "E41")
                // 修订号 = 变更时间毫秒 + 资料结构版本（V2 加了 fundsPath）：near-pay 对同修订不同内容判冲突
                .containsEntry("revision", 1_762_300_000_001L).containsEntry("deleted", false)
                .containsEntry("eventId", "ais-ST41-1762300000001").containsEntry("sourceScope", "MAIN");
        // near-pay Canonical: sorted keys, nulls dropped, compact Jackson JSON, SHA-256 hex
        String canonical = "{\"address\":\"台北市中山區 1樓\",\"entityName\":\"ume 股份有限公司\",\"fundsPath\":\"DIRECT\",\"logoUrl\":\"https://img.example/uploads/logo.png\","
                + "\"market\":\"TW\",\"name\":\"ume早餐店\",\"openHours\":\"07:00-14:00\",\"status\":\"ACTIVE\",\"storeCode\":\"ume-breakfast\"}";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(body.get("profileHash")).isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fundsPathIsConfigurableAndValidated() {
        var aggregated = new StoreSyncProperties(true, "http://x", null, "cred", null, null, null, null, null, "AGGREGATED");
        var body = NearPayStoreProfile.request(snap(null, null, null, "ACTIVE", false), aggregated);
        assertThat((Map<String, Object>) body.get("profile")).containsEntry("fundsPath", "AGGREGATED");
        assertThatThrownBy(() -> new StoreSyncProperties(true, "http://x", null, "cred", null, null, null, null, null, "SPLIT"))
                .hasMessageContaining("shop.nearpay.store-sync.funds-path");
    }

    @Test
    void statusLogoAndStoreCodeRules() {
        assertThat(NearPayStoreProfile.status(snap(null, null, null, "SUSPENDED", false))).isEqualTo("DISABLED");
        assertThat(NearPayStoreProfile.status(snap(null, null, null, "READONLY", false))).isEqualTo("READONLY");
        assertThat(NearPayStoreProfile.status(snap(null, null, null, "ACTIVE", true))).isEqualTo("DELETED");
        assertThat(NearPayStoreProfile.logo(snap(null, "https://cdn.example/b.jpg", null, "ACTIVE", false), PROPS)).isEqualTo("https://cdn.example/b.jpg");
        assertThat(NearPayStoreProfile.logo(snap("http://insecure/x.png", null, null, "ACTIVE", false), PROPS)).isNull();
        var noBase = new StoreSyncProperties(true, "http://x", null, "cred", null, null, null, null, null);
        assertThat(NearPayStoreProfile.logo(snap("/uploads/x.png", null, null, "ACTIVE", false), noBase)).isNull();
        assertThat(NearPayStoreProfile.storeCode("ab")).isNull();
        assertThat(NearPayStoreProfile.storeCode("a_b_c")).isNull();
        assertThat(NearPayStoreProfile.storeCode(" Ume-Breakfast ")).isEqualTo("ume-breakfast");
    }

    @SuppressWarnings("unchecked")
    static HttpClient http(int status, AtomicReference<HttpRequest> seen) throws Exception {
        var client = mock(HttpClient.class);
        var resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn("{\"status\":\"APPLIED\"}");
        when(client.send(any(), any())).thenAnswer(i -> { seen.set(i.getArgument(0)); return resp; });
        return client;
    }

    static SysOutbox event() {
        var e = new SysOutbox();
        e.setAggregateId("ST41");
        e.setEventType("MERCHANT_STORE_CHANGED");
        return e;
    }

    @Test
    void consumerPostsWithInternalHeadersAndRetriesOnlyServerErrors() throws Exception {
        var reader = mock(StoreSnapshotReader.class);
        when(reader.read("ST41")).thenReturn(Optional.of(snap(null, null, "ume-breakfast", "ACTIVE", false)));
        var seen = new AtomicReference<HttpRequest>();
        new NearPayStoreSyncConsumer(reader, PROPS, "tok", http(200, seen)).consume(event());
        assertThat(seen.get().uri().toString()).isEqualTo("http://127.0.0.1:18093/internal/pay/v2/store-projections/sync");
        assertThat(seen.get().headers().firstValue("X-Internal-Token")).contains("tok");
        assertThat(seen.get().headers().firstValue("X-Internal-Caller")).contains("AI_SHOP_UME");
        assertThat(seen.get().headers().firstValue("X-Internal-Credential")).contains("cred");
        assertThatThrownBy(() -> new NearPayStoreSyncConsumer(reader, PROPS, "tok", http(503, seen)).consume(event()))
                .hasMessageContaining("HTTP 503");
        new NearPayStoreSyncConsumer(reader, PROPS, "tok", http(409, seen)).consume(event()); // logged, not retried
        when(reader.read("ST41")).thenReturn(Optional.empty());
        new NearPayStoreSyncConsumer(reader, PROPS, "tok", http(200, seen)).consume(event());
        assertThat(new NearPayStoreSyncConsumer(reader, PROPS, "tok", http(200, seen)).supports("MERCHANT_STORE_CHANGED")).isTrue();
        assertThatThrownBy(() -> new NearPayStoreSyncConsumer(reader, PROPS, "", http(200, seen))).hasMessageContaining("internal-token");
    }

    @Test
    void springCanCreateEnabledSyncBeans() {
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t", Map.of(
                    "shop.nearpay.store-sync.enabled", "true", "shop.nearpay.store-sync.base-url", "http://127.0.0.1:18093",
                    "shop.nearpay.store-sync.credential", "cred", "shop.services.internal-token", "tok")));
            ctx.registerBean(javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class));
            ctx.registerBean(ai.neargo.shop.event.OutboxEventBus.class, () -> mock(ai.neargo.shop.event.OutboxEventBus.class));
            ctx.register(StoreSyncConfiguration.class, StoreSnapshotReader.class, NearPayStoreSyncConsumer.class);
            ctx.refresh();
            assertThat(ctx.getBean(NearPayStoreSyncConsumer.class).supports("MERCHANT_STORE_CHANGED")).isTrue();
            assertThat(ctx.getBean(StoreSyncProperties.class).credential()).isEqualTo("cred");
        }
    }
}
