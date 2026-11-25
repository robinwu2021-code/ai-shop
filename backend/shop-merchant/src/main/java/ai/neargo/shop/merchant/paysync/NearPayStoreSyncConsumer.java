package ai.neargo.shop.merchant.paysync;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.spi.merchant.StoreChanged;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 把门店当前快照推给 near-pay V2-02（{@code POST /internal/pay/v2/store-projections/sync}）。至少一次投递：
 * near-pay 按修订与事件号幂等（重复 = DUPLICATE，旧修订 = STALE，都算成功）。
 * 网络错误、429 和 5xx 由 outbox 重试；其他拒绝只记录。
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

    @org.springframework.beans.factory.annotation.Autowired
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
        var payload = NearPayStoreProfile.request(snapshot, props);
        Object revision = payload.get("revision");
        String body = JSON.writeValueAsString(payload);
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
        } catch (IOException e) {
            // 门店同步按事件号与版本幂等：不可达、超时都可以原样重投（RetryableOutboxException 走退避重试，不停在「结果未知」）
            throw new RetryableOutboxException("near-pay store sync unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("near-pay store sync interrupted", e);
        }
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            if (log.isInfoEnabled()) {
                log.info("nearpay.store.sync storeNo={} revision={} httpStatus={} resultCode={}",
                        snapshot.storeNo(), revision, code, successCode(resp.body()));
            }
            return;
        }
        if (code >= 500 || code == 429) {
            // 支付侧并发死锁等返回 5xx（UME166 2026-11-19 批量同步）：同上，幂等可重投
            log.warn("nearpay.store.sync storeNo={} revision={} httpStatus={} resultCode={}",
                    snapshot.storeNo(), revision, code, code == 429 ? "RATE_LIMITED" : "SERVER_ERROR");
            throw new RetryableOutboxException("near-pay store sync HTTP " + code);
        }
        log.error("nearpay.store.sync storeNo={} revision={} httpStatus={} resultCode=HTTP_REJECTED",
                snapshot.storeNo(), revision, code);
    }

    // This status is diagnostic only. HTTP status still controls acknowledgment and retry.
    private static String successCode(String body) {
        if (body == null || body.isBlank()) {
            return "UNKNOWN_STATUS";
        }
        try {
            return switch (JSON.readTree(body).path("status").asString("")) {
                case "APPLIED" -> "APPLIED";
                case "DUPLICATE" -> "DUPLICATE";
                case "STALE" -> "STALE";
                default -> "UNKNOWN_STATUS";
            };
        } catch (JacksonException e) {
            return "UNKNOWN_STATUS";
        }
    }
}
