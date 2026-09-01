package ai.neargo.shop.svc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 进程之间调用的那一半 —— <b>只管「怎么调」，不管「调哪里」</b>。
 *
 * <h2>四条规矩，与 {@code JobHandlerEndpoint} 那边一致</h2>
 * <ol>
 *   <li><b>不经 nginx</b>：内部调用走 {@code 127.0.0.1}。
 *       nginx 只做外部入口（域名、TLS、静态资源、三端路径）——
 *       让内部调用绕到 nginx 再回来，多一跳、多一份配置，
 *       而且加一个服务要 reload 全站；</li>
 *   <li><b>共享密钥，不是用户令牌</b>：这条链路不认任何用户身份；</li>
 *   <li><b>不记 body</b>：内部调用的参数可能带业务标识，
 *       调用链路的日志不该成为一个额外的数据出口；</li>
 *   <li><b>HTTP/1.1</b>：JDK 的 HttpClient 在 HTTP/2 下发大 body 会挂
 *       （本仓库踩过，见 shop-job 那边同样的写法）。</li>
 * </ol>
 *
 * <h2>三种失败要分开</h2>
 * <b>没配地址</b>（改配置）、<b>连不上</b>（等对方起来）、
 * <b>对方返回错误</b>（看对方日志）—— 混成一种的话，
 * 运维会守着一个永远不来的恢复。{@link Result} 上把它们分开。
 */
@Component
public class InternalClient {

    private static final Logger log = LoggerFactory.getLogger(InternalClient.class);

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ServiceLocator locator;

    /**
     * 共享密钥。**没配就一律失败**，不是「没配就不校验」——
     * 后者的表现是内部口对任何人开放，而且没有任何症状。
     */
    @Value("${shop.services.internal-token:}")
    private String token = "";

    public InternalClient(ServiceLocator locator) {
        this.locator = locator;
    }

    /**
     * @param service    服务名（{@link ServiceName}）
     * @param path       以 {@code /} 开头，例如 {@code /internal/order/paid}
     * @param jsonBody   请求体
     * @param timeoutSec 读超时
     */
    public Result post(String service, String path, String jsonBody, int timeoutSec) {
        var base = locator.baseUrlOf(service);
        if (base.isEmpty()) {
            /*
             * **配置缺失单独一种**。它与「连不上」的区别是：这个不会自己好。
             */
            return new Result(Outcome.NOT_CONFIGURED, 0, null,
                    "没有配置服务 " + service + " 的地址（shop.services.targets." + service + "）");
        }
        if (token.isBlank()) {
            return new Result(Outcome.NOT_CONFIGURED, 0, null,
                    "shop.services.internal-token 没配 —— 内部调用一律拒绝");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base.get() + path))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, token)
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return new Result(Outcome.OK, res.statusCode(), res.body(), null);
            }
            return new Result(Outcome.REMOTE_ERROR, res.statusCode(), res.body(),
                    service + " 返回 " + res.statusCode());
        } catch (java.net.http.HttpTimeoutException e) {
            return new Result(Outcome.TIMEOUT, 0, null, "调用 " + service + " 超时 " + timeoutSec + "s");
        } catch (Exception e) {
            // 不记 body，也不记异常堆栈里可能带的 URL 参数
            log.warn("内部调用失败 service={} path={} 异常={}", service, path, e.getClass().getSimpleName());
            return new Result(Outcome.UNREACHABLE, 0, null,
                    "连不上 " + service + "：" + e.getClass().getSimpleName());
        }
    }

    /** 失败的三种：改配置 / 等对方 / 看对方日志 */
    public enum Outcome {
        OK,
        /** 地址或密钥没配 —— <b>不会自己好</b> */
        NOT_CONFIGURED,
        /** 连不上，对方多半没起来 */
        UNREACHABLE,
        TIMEOUT,
        /** 对方应答了，但是个错误 */
        REMOTE_ERROR,
    }

    public record Result(Outcome outcome, int statusCode, String body, String message) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }
}
