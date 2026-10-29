package ai.neargo.inventory.client;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.svc.client.ServiceClientSpec;
import ai.neargo.svc.client.ServiceClients;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 库存服务预留的客户端（M8 T8.3）：一次调用 = 熔断判断 → HTTP → 结局归类。
 *
 * <ul>
 *   <li><b>业务错误原样回来</b>：服务的错误体 {@code {code, message, data.args}} 翻回 {@link InventoryException}，
 *       宿主在自己的边界上再翻成自己的码（商城与内嵌时期同一套翻译）；</li>
 *   <li><b>传输失败一律 {@link InventoryError#UNAVAILABLE}</b>（连不上、超时、5xx、熔断中），宿主据此拒单；</li>
 *   <li><b>不重试</b>：服务按单号幂等，要重试由调用方决定（订单侧的恢复任务会再来）。</li>
 * </ul>
 */
public final class InventoryReservationClient {

    public static final String SERVICE = "inventory";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InventoryReservationApi api;
    private final InventoryStockApi stock;
    private final InventoryConsoleApi consoleApi;
    private final CircuitBreaker breaker;

    InventoryReservationClient(InventoryReservationApi api, InventoryStockApi stock, InventoryConsoleApi consoleApi,
                               CircuitBreaker breaker) {
        this.api = api;
        this.stock = stock;
        this.consoleApi = consoleApi;
        this.breaker = breaker;
    }

    /**
     * @param baseUrl   每次调用时现查（没配地址在调用时报，不是启动失败）
     * @param appKey    系统凭证
     */
    public static InventoryReservationClient create(Supplier<Optional<String>> baseUrl, String appKey,
                                                    String appSecret, Duration connectTimeout,
                                                    Duration readTimeout, int breakerThreshold,
                                                    Duration breakerOpenFor) {
        ServiceClientSpec spec = new ServiceClientSpec(SERVICE, s -> baseUrl.get(), "Authorization",
                () -> appKey == null || appKey.isBlank() ? null : "Bearer " + appKey + ":" + appSecret, true,
                connectTimeout, readTimeout, "shop.inventory.service.base-url", "shop.inventory.service.app-key",
                List.<ClientHttpRequestInterceptor>of(InventoryReservationClient::decodeErrors));
        return new InventoryReservationClient(ServiceClients.create(InventoryReservationApi.class, spec),
                ServiceClients.create(InventoryStockApi.class, spec),
                ServiceClients.create(InventoryConsoleApi.class, spec),
                new CircuitBreaker(breakerThreshold, breakerOpenFor, Clock.systemUTC()));
    }

    /** 测试与自定义传输用：直接给 API 实现 */
    public static InventoryReservationClient of(InventoryReservationApi api, CircuitBreaker breaker) {
        return new InventoryReservationClient(api, null, null, breaker);
    }

    public static InventoryReservationClient of(InventoryReservationApi api, InventoryStockApi stock,
                                                CircuitBreaker breaker) {
        return new InventoryReservationClient(api, stock, null, breaker);
    }

    public static InventoryReservationClient of(InventoryReservationApi api, InventoryStockApi stock,
                                                InventoryConsoleApi console, CircuitBreaker breaker) {
        return new InventoryReservationClient(api, stock, console, breaker);
    }

    /** 商家后台转发：这个商家（{@code ownerRef}）、这个操作人 */
    public InventoryConsole console(String ownerRef, String actor) {
        return new InventoryConsole(consoleApi, this::call, ownerRef, actor);
    }

    public InventoryReservationApi.View reserve(String ownerRef, String actor, InventoryReservationApi.Reserve body) {
        return call(() -> api.reserve(ownerRef, key(), actor, body));
    }

    /** @return 没有这笔预留为空 */
    public Optional<InventoryReservationApi.View> query(String ownerRef, String ref) {
        try {
            return Optional.of(call(() -> api.query(ownerRef, ref)));
        } catch (InventoryException e) {
            // 只有「这笔预留没有」才是空；业主没有、没翻过来等照样抛 —— 不能把配置错当成「没预留」
            if (e.error() == InventoryError.NOT_FOUND && e.args() != null && e.args().length > 0
                    && "reservation".equals(String.valueOf(e.args()[0]))) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public InventoryReservationApi.View acceptUnpaid(String ownerRef, String actor, String ref) {
        return call(() -> api.acceptUnpaid(ownerRef, key(), actor, ref));
    }

    public InventoryReservationApi.View allocatePaid(String ownerRef, String actor, String ref) {
        return call(() -> api.allocatePaid(ownerRef, key(), actor, ref));
    }

    public InventoryReservationApi.View issue(String ownerRef, String actor, String ref, String fulfillmentRef) {
        return call(() -> api.issue(ownerRef, key(), actor, ref, new InventoryReservationApi.Issue(fulfillmentRef)));
    }

    public InventoryReservationApi.View cancel(String ownerRef, String actor, String ref) {
        return call(() -> api.cancel(ownerRef, key(), actor, ref));
    }

    // ── 系统级：按单号 / 履约单号，碰本系统所有已翻到服务的商家 ─────────────────────

    public void releaseByRef(String actor, String ref) {
        call(() -> stock.releaseByRef(actor, ref));
    }

    /** @return 第一张销售出库单号；没有为空 */
    public String commitByRef(String actor, String ref) {
        return call(() -> stock.commitByRef(actor, ref)).get("outboundNo");
    }

    public void allocatePaidByRef(String actor, String ref) {
        call(() -> stock.allocatePaidByRef(actor, ref));
    }

    public String issueByFulfillment(String actor, String fulfillmentRef) {
        return call(() -> stock.issueByFulfillment(actor, fulfillmentRef)).get("outboundNo");
    }

    public void releaseAllocatedByFulfillment(String actor, String fulfillmentRef) {
        call(() -> stock.releaseAllocatedByFulfillment(actor, fulfillmentRef));
    }

    // ── 按商家 ─────────────────────────────────────────────────────────────────

    public InventoryReservationApi.View releaseAllocatedTo(String ownerRef, String actor, String ref,
                                                           InventoryStockApi.ReleaseTo body) {
        return call(() -> stock.releaseAllocatedTo(ownerRef, key(), actor, ref, body));
    }

    public InventoryStockApi.Restored restore(String ownerRef, String actor, InventoryStockApi.Restore body) {
        return call(() -> stock.restore(ownerRef, key(), actor, body));
    }

    public void setOnHand(String ownerRef, String actor, InventoryStockApi.OnHand body) {
        call(() -> stock.setOnHand(ownerRef, key(), actor, body));
    }

    public boolean breakerOpen() {
        return breaker.open();
    }

    private <T> T call(Supplier<T> invocation) {
        if (!breaker.allow()) {
            throw InventoryException.of(InventoryError.UNAVAILABLE, "circuit_open");
        }
        try {
            T result = ServiceCalls.call(SERVICE, invocation);
            breaker.onSuccess();
            return result;
        } catch (InventoryException business) {
            // 服务应答了一个业务错误：服务是好的
            breaker.onSuccess();
            throw business;
        } catch (ServiceCallException transport) {
            breaker.onFailure();
            throw InventoryException.of(InventoryError.UNAVAILABLE, transport.outcome().name());
        }
    }

    /** 幂等键：服务按单号本身幂等，这里每次一把新键，只防同一次请求在传输层被重放 */
    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    /** 4xx 且错误体能认出码 → 翻回 {@link InventoryException}；其余交给传输层按 REMOTE_ERROR 处理 */
    public static org.springframework.http.client.ClientHttpResponse decodeErrors(
            org.springframework.http.HttpRequest request, byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution) throws java.io.IOException {
        var response = execution.execute(request, body);
        int status = response.getStatusCode().value();
        if (status < 400 || status >= 500) {
            return response;
        }
        InventoryError error = null;
        List<Object> args = new ArrayList<>();
        try {
            JsonNode node = JSON.readTree(response.getBody().readAllBytes());
            error = InventoryError.byKey(node.path("code").asString(""));
            node.path("data").path("args").forEach(a -> args.add(a.asString()));
        } catch (RuntimeException unreadable) {
            // 读不懂的错误体：当作传输层的远端错误
        }
        if (error == null) {
            throw new ServiceCallException(ai.neargo.svc.client.CallOutcome.REMOTE_ERROR, SERVICE, status,
                    SERVICE + " 返回 " + status);
        }
        throw InventoryException.of(error, args.toArray());
    }
}
