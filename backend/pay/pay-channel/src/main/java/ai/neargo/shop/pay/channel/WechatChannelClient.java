package ai.neargo.shop.pay.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 微信支付 APIv3 的 HTTP 调用与签名。{@link ChannelClient} 的生产实现。
 *
 * <h2>密钥不在这里</h2>
 * 私钥只由 {@link WechatApiV3Signer} 持有（装配见 {@link WechatPayChannelConfig}）。
 * 本类<b>不接受也不返回任何密钥</b>，异常信息里也不带签名串与报文主体 ——
 * 日志会被采集、会被转发、会被整段贴进工单。
 *
 * <h2>三件容易被省掉、而省掉不报错的事</h2>
 * <ol>
 *   <li><b>query 参与签名</b>：查单接口的 {@code ?mchid=} 必须进待签串。
 *       漏掉的表现是签名错，而错误信息指向证书。</li>
 *   <li><b>验应答签名</b>：不验的话，能改我方出网流量的人可以把「下单失败」
 *       改成「下单成功」，我方会照着假回执给用户一个付不了的收银台。</li>
 *   <li><b>主备域名切换</b>：微信明确要求商户实现。只在 <b>IO 失败</b>时切 ——
 *       HTTP 4xx 换个域名参数还是错的，重发一次只是多一条一样的失败。</li>
 * </ol>
 */
@Component("wechatChannelClient")
@ConditionalOnProperty(name = "shop.pay.wechat.enabled", havingValue = "true")
public class WechatChannelClient implements ChannelClient {

    private static final Logger log = LoggerFactory.getLogger(WechatChannelClient.class);

    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    /** 微信要求带 User-Agent，缺了会被拒 */
    private static final String USER_AGENT = "ai-shop/1.0 (wechatpay-apiv3)";

    private final HttpClient http;
    private final WechatApiV3Signer signer;
    private final ObjectMapper json;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;

    public WechatChannelClient(
            WechatApiV3Signer signer,
            @Value("${shop.pay.wechat.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${shop.pay.wechat.read-timeout-seconds:15}") int readTimeoutSeconds,
            ObjectMapper json) {
        this.signer = signer;
        this.json = json;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.http = HttpClient.newBuilder()
                // **锁 HTTP/1.1**：微信网关走 1.1，而 Java 的 HttpClient 在协商 HTTP/2
                // 时对大 body 有已知的挂起问题。这里不需要 2 的任何好处
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    @Override
    public Map<String, Object> post(String api, Map<String, Object> body) throws ChannelException {
        String payload = writeJson(body);
        return call(METHOD_POST, api, payload);
    }

    @Override
    public Map<String, Object> get(String api) throws ChannelException {
        // GET 的待签 body 是**空串**，不是没有那一行
        return call(METHOD_GET, api, "");
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> call(String method, String api, String body) {
        try {
            return send(WechatDirectApis.HOST, method, api, body);
        } catch (IOException | InterruptedException e) {
            /*
             * 只有 IO 失败才切备域名。**这里不能捕 ChannelException** ——
             * 那是通道已经答话了（4xx/5xx/验签不过），换个域名答案一样,
             * 重发只是把一笔可能已经受理的请求再发一次。
             */
            log.warn("[wechat] 主域名不可达，切备域名重试一次：{} {}（{}）",
                    method, api, e.getClass().getSimpleName());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                return send(WechatDirectApis.HOST_BACKUP, method, api, body);
            } catch (IOException | InterruptedException e2) {
                if (e2 instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new ChannelException("微信通道不可达（主备域名均失败）："
                        + e2.getClass().getSimpleName(), true);
            }
        }
    }

    private Map<String, Object> send(String host, String method, String api, String body)
            throws IOException, InterruptedException {
        String nonce = WechatApiV3Signer.nonce();
        long ts = System.currentTimeMillis() / 1000;
        // **待签的是含 query 的路径**，不是主机名后的裸路径去掉 query
        String auth = signer.authorization(method, api, body, nonce, ts);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(host + api))
                .timeout(Duration.ofSeconds(readTimeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", auth);
        if (METHOD_GET.equals(method)) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return handle(method, api, resp);
    }

    private Map<String, Object> handle(String method, String api, HttpResponse<String> resp) {
        int status = resp.statusCode();
        String text = resp.body() == null ? "" : resp.body();

        if (status < 200 || status >= 300) {
            /*
             * **可重试的边界画在这里**：5xx 与 429 是「通道现在不行」，
             * 4xx 是「这个请求本身不行」。不区分的话，不可重试的失败会一直占着
             * 重试队列，而真正该人工介入的单没人看。
             */
            boolean retryable = status >= 500 || status == 429;
            throw new ChannelException("微信 " + api + " HTTP " + status + "：" + failureOf(text), retryable);
        }

        /*
         * 验应答签名。**验不过等同于没收到** —— 不能把内容当真。
         *
         * 这里没有「配了公钥才验」的分支：公钥缺失在装配期就被
         * WechatPayChannelConfig 拒了。留那个分支的话，少配一项就等于
         * 整条出站流量不设防，而日志上只有一行 WARN。
         */
        if (!signer.verifyResponse(
                header(resp, "Wechatpay-Timestamp"),
                header(resp, "Wechatpay-Nonce"),
                text,
                header(resp, "Wechatpay-Signature"))) {
            // 不带出 body：验不过的内容来源不明，不该进日志
            throw new ChannelException("微信 " + api + " 应答验签失败", false);
        }

        if (text.isBlank()) {
            // 204 之类：没有内容也算成功，交给调用方按缺字段判
            return Map.of();
        }
        try {
            return json.readValue(text, Map.class);
        } catch (RuntimeException e) {
            throw new ChannelException("微信 " + api + " 应答不是 JSON（HTTP " + status + "）", false);
        }
    }

    /** 从错误应答里取 {@code code}，取不到就说不知道。**不回显整个 body**。 */
    private String failureOf(String text) {
        if (text == null || text.isBlank()) {
            return "无应答体";
        }
        try {
            Map<?, ?> m = json.readValue(text, Map.class);
            Object code = m.get("code");
            Object message = m.get("message");
            return (code == null ? "未知错误码" : String.valueOf(code))
                    + (message == null ? "" : "（" + message + "）");
        } catch (RuntimeException e) {
            return "应答不是 JSON";
        }
    }

    /** HTTP 头大小写不敏感，{@code HttpHeaders#firstValue} 已经按此处理。 */
    private static String header(HttpResponse<String> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body == null ? Map.of() : body);
        } catch (RuntimeException e) {
            throw new ChannelException("微信请求体序列化失败", false);
        }
    }
}
