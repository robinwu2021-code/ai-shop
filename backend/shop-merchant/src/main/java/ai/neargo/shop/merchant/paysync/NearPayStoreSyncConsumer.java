package ai.neargo.shop.merchant.paysync;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.merchant.StoreChanged;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 把门店当前快照推给 near-pay V2-02（{@code POST /internal/pay/v2/store-projections/sync}）。至少一次投递：
 * near-pay 按修订与事件号幂等（重复 = DUPLICATE，旧修订 = STALE，都算成功）。
 * 网络错误 / 5xx 抛出由 outbox 重试；4xx（参数、内容冲突、未登记调用方）记错误不重试 —— 重试也不会变好。
 */
@Component
@ConditionalOnProperty(prefix = "shop.nearpay.store-sync", name = "enabled", havingValue = "true")
public class NearPayStoreSyncConsumer implements OutboxConsumer {

    static final String PATH = "/internal/pay/v2/store-projections/sync";
    private static final Logger log = LoggerFactory.getLogger(NearPayStoreSyncConsumer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StoreSnapshotReader reader;
    private final StoreSyncProperties props;
    private final String token;
    private final HttpClient http;

    public NearPayStoreSyncConsumer(StoreSnapshotReader reader, StoreSyncProperties props,
                                    @Value("${shop.services.internal-token:}") String token) {
        this(reader, props, token, HttpClient.newBuilder().connectTimeout(props.timeout()).build());
    }

    NearPayStoreSyncConsumer(StoreSnapshotReader reader, StoreSyncProperties props, String token, HttpClient http) {
        if (props.baseUrl() == null || props.baseUrl().isBlank() || token == null || token.isBlank()
                || props.credential() == null || props.credential().isBlank()) {
            throw new IllegalStateException("shop.nearpay.store-sync requires base-url, credential and shop.services.internal-token");
        }
        this.reader = reader;
        this.props = props;
        this.token = token;
        this.http = http;
    }

    @Override
    public String handlerId() { return "nearpay-store-sync"; }

    @Override
    public boolean externalWorkflow() { return true; }

    @Override
    public boolean supports(String eventType) { return StoreChanged.TYPE.equals(eventType); }

    @Override
    public void consume(SysOutbox event) {
        var snapshot = reader.read(event.getAggregateId()).orElse(null);
        if (snapshot == null) {
            log.warn("[nearpay-store-sync] store {} no longer exists; skipped", event.getAggregateId());
            return;
        }
        String body = JSON.writeValueAsString(NearPayStoreProfile.request(snapshot, props));
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + PATH))
                .timeout(props.timeout())
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", token)
                .header("X-Internal-Caller", props.caller())
                .header("X-Internal-Credential", props.credential())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("near-pay store sync unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("near-pay store sync interrupted", e);
        }
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            log.info("[nearpay-store-sync] store {} revision {} -> {}", snapshot.storeNo(), snapshot.revision(), brief(resp.body()));
            return;
        }
        if (code >= 500 || code == 429) {
            throw new IllegalStateException("near-pay store sync HTTP " + code);
        }
        log.error("[nearpay-store-sync] store {} revision {} rejected HTTP {}: {}", snapshot.storeNo(), snapshot.revision(), code, brief(resp.body()));
    }

    private static String brief(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
