package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.svc.ServiceLocator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 透传型转发（TDD-支付运营端经网关转发与权限下沉 §2.3）：网关只校验登录，请求与响应原样过网关，权限由下游自己判。
 *
 * <p>与 {@link HttpProxyInvoker} 的区别，正是它不适用于支付运营接口的四处：
 * <ul>
 *   <li>带上终端的 {@code Authorization}：下游（near-pay）拿它去统一认证接口换身份与权限码；</li>
 *   <li>不再包一层统一包体：下游自带 {@code {code,data}}；</li>
 *   <li>下游的 4xx/5xx 原样回给终端：前端靠 near-pay 的 {@code reasonCode}（如 {@code VERSION_CONFLICT}）给提示；</li>
 *   <li>请求体按字节转（JSON、multipart 上传都行），响应按字节回（JSON、图片、PDF 都行）。</li>
 * </ul>
 * 只转白名单里的请求头与响应头；{@code Cookie}、{@code Host} 与逐跳头不转。网关自己的问题（下游连不上、超时、请求体超限）
 * 回 502 / 504 / 413，包体与 near-pay 的错误同形（{@code message}、{@code reasonCode}），前端不用分两种读法。
 */
public class PassThroughInvoker extends AbstractServiceInvoker {

    private static final Logger log = LoggerFactory.getLogger(PassThroughInvoker.class);
    /** 进件附件 5 MiB（near-pay 上限）加 multipart 开销的余量 */
    static final int MAX_BODY = 8 * 1024 * 1024;
    static final String HANDLER = "pass-through";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    static final List<String> REQUEST_HEADERS = List.of("Authorization", "Content-Type", "Accept", "Accept-Language", "X-Request-Id");
    static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Content-Disposition", "Cache-Control", "Pragma",
            "X-Content-Type-Options", "Content-Security-Policy", "X-Frame-Options", "X-Request-Id");

    private final ServiceLocator locator;
    private final HttpClient client;
    private final Duration readTimeout;
    private final List<String> requestHeaders;

    public PassThroughInvoker(ServiceLocator locator) {
        this(locator, List.of());
    }

    /**
     * @param extraRequestHeaders 这一端下游还要的请求头，追加在 {@link #REQUEST_HEADERS} 之后
     *                            （B 端：App 选中的 {@code X-Merchant-No}、{@code X-Store-No}）
     */
    public PassThroughInvoker(ServiceLocator locator, List<String> extraRequestHeaders) {
        this(locator, READ_TIMEOUT, extraRequestHeaders);
    }

    PassThroughInvoker(ServiceLocator locator, Duration readTimeout) {
        this(locator, readTimeout, List.of());
    }

    private PassThroughInvoker(ServiceLocator locator, Duration readTimeout, List<String> extraRequestHeaders) {
        this.locator = locator;
        this.readTimeout = readTimeout;
        this.requestHeaders = Stream.concat(REQUEST_HEADERS.stream(), extraRequestHeaders.stream()).toList();
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * 一条透传路由：{@code pattern} 与 {@code target} 都以 {@code /**} 结尾，前缀之后的路径原样接到目标前缀后面。
     *
     * @param pattern 对外地址，如 {@code /ops/pay-ops/v2/**}
     * @param service 下游服务名，如 {@code PAY}
     * @param target  下游地址，如 {@code /pay-ops/v2/**}
     */
    public static ApiResource route(String pattern, String service, String target) {
        if (!pattern.endsWith("/**") || !target.endsWith("/**")) {
            throw new IllegalArgumentException("透传路由的对外地址与下游地址都要以 /** 结尾");
        }
        return new ApiResource("*", pattern, service, ai.neargo.shop.gateway.core.end.Access.LOGIN, null, HANDLER, target);
    }

    /** 只接手 {@link #route} 造出来的路由；不会被提前判权的调用列表误选去转库里的微服务链接。 */
    @Override
    public boolean supports(ApiResource resource) {
        return HANDLER.equals(resource.handler()) && resource.remote();
    }

    @Override
    protected void doInvoke(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws IOException {
        String base = locator.baseUrlOf(resource.location())
                .orElseThrow(() -> new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY,
                        "没有配置服务地址 shop.services.targets." + resource.location(), null));
        byte[] body = readBody(request);
        if (body == null) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE", "请求内容超过 8 MiB");
            return;
        }
        HttpRequest.Builder outbound = HttpRequest.newBuilder(URI.create(base + targetOf(resource, request)))
                .timeout(readTimeout)
                .method(request.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : requestHeaders) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                outbound.header(name, value);
            }
        }
        HttpResponse<InputStream> downstream;
        try {
            downstream = client.send(outbound.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new DownstreamException(HttpServletResponse.SC_GATEWAY_TIMEOUT, "下游超时", e);
        } catch (IOException e) {
            throw new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY, "下游连不上", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY, "调用被中断", e);
        }
        response.setStatus(downstream.statusCode());
        for (String name : RESPONSE_HEADERS) {
            downstream.headers().firstValue(name).ifPresent(v -> response.setHeader(name, v));
        }
        try (InputStream in = downstream.body(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @Override
    protected void after(ApiResource resource, HttpServletRequest request, HttpServletResponse response, long elapsedNanos) {
        log.info("透传 {} {} → {} · {} · {} ms", request.getMethod(), resource.pattern(), resource.location(),
                response.getStatus(), elapsedNanos / 1_000_000);
    }

    @Override
    protected void onError(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                           DownstreamException error) throws IOException {
        log.warn("透传失败 {} {} → {} · {}", request.getMethod(), resource.pattern(), resource.location(), error.getMessage());
        boolean timeout = error.httpStatus() == HttpServletResponse.SC_GATEWAY_TIMEOUT;
        writeError(response, error.httpStatus(), timeout ? "UPSTREAM_TIMEOUT" : "UPSTREAM_UNAVAILABLE",
                timeout ? "支付服务响应超时，请稍后重试" : "支付服务暂不可用，请稍后重试");
    }

    /** 下游地址：对外前缀换成目标前缀，后面的路径与查询串原样（保持原始编码）。 */
    static String targetOf(ApiResource resource, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String from = resource.pattern().substring(0, resource.pattern().length() - 3);
        String to = resource.targetPath().substring(0, resource.targetPath().length() - 3);
        if (!path.startsWith(from)) {
            throw new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY, "地址不在透传前缀下 " + path, null);
        }
        String target = to + path.substring(from.length());
        return request.getQueryString() == null ? target : target + "?" + request.getQueryString();
    }

    /** 读满请求体；超过上限返回 null */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        return body.length > MAX_BODY ? null : body;
    }

    private static void writeError(HttpServletResponse response, int status, String reasonCode, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String json = "{\"code\":" + status + "00,\"message\":\"" + message + "\",\"reasonCode\":\"" + reasonCode + "\",\"data\":null}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }
}
