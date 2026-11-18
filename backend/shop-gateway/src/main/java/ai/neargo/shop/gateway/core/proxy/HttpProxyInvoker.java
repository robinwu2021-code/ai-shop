package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.svc.ServiceLocator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * 透传：把已经判过权的请求转给微服务，再把结果按统一包体回给终端（TDD-网关层与统一权限 §2.8）。
 *
 * <p>一期的边界：
 * <ul>
 *   <li>请求：JSON 或没有请求体；请求体读进内存，超过 {@link #MAX_BODY} 直接拒绝。</li>
 *   <li>带给下游的身份：进程凭据 {@code X-Internal-Token}，以及操作人与端两个头。
 *       <b>这两个头没有签名</b>，下游只能当作留痕用；带签名的上下文在后面一期加。终端的 {@code Authorization} 不下传。</li>
 *   <li>响应：下游成功时把它的 JSON 放进统一包体的 data；下游的错误不原样给终端。</li>
 *   <li>不支持文件上传、SSE、WebSocket：这三类继续写本进程入口。</li>
 * </ul>
 */
public class HttpProxyInvoker extends AbstractServiceInvoker {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyInvoker.class);
    static final int MAX_BODY = 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final ServiceLocator locator;
    private final String internalToken;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public HttpProxyInvoker(ServiceLocator locator, String internalToken, ObjectMapper json) {
        this.locator = locator;
        this.internalToken = internalToken;
        this.json = json;
    }

    @Override
    public boolean supports(ApiResource resource) {
        return resource.remote();
    }

    @Override
    protected void doInvoke(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws IOException {
        String base = locator.baseUrlOf(resource.location())
                .orElseThrow(() -> new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY,
                        "没有配置服务地址 shop.services.targets." + resource.location(), null));
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        HttpRequest.Builder outbound = HttpRequest.newBuilder(URI.create(base + targetPath(resource, request)))
                .timeout(READ_TIMEOUT)
                .method(request.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (request.getContentType() != null) {
            outbound.header("Content-Type", request.getContentType());
        }
        outbound.header("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (internalToken != null && !internalToken.isBlank()) {
            outbound.header("X-Internal-Token", internalToken);
        }
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        if (user != null) {
            outbound.header("X-Gateway-Operator", user.userNo());
            outbound.header("X-Gateway-Realm", user.realm().name());
        }
        HttpResponse<byte[]> downstream;
        try {
            downstream = client.send(outbound.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new DownstreamException(HttpServletResponse.SC_GATEWAY_TIMEOUT, "下游超时", e);
        } catch (IOException e) {
            throw new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY, "下游连不上", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY, "调用被中断", e);
        }
        if (downstream.statusCode() / 100 != 2) {
            throw new DownstreamException(HttpServletResponse.SC_BAD_GATEWAY,
                    "下游回了 " + downstream.statusCode(), null);
        }
        Object data = downstream.body().length == 0 ? null : json.readTree(downstream.body());
        write(response, HttpServletResponse.SC_OK, ApiResult.ok(data));
    }

    @Override
    protected void onError(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                           DownstreamException error) throws IOException {
        // 下游的原始错误不给终端：只说「内部错误」，细节在日志里
        log.warn("透传失败 {} {} → {} · {}", request.getMethod(), resource.pattern(), resource.location(),
                error.getMessage());
        write(response, error.httpStatus(),
                ApiResult.error(ErrorCode.INTERNAL_ERROR.code(), Messages.get(ErrorCode.INTERNAL_ERROR.msgKey())));
    }

    /** 内部地址：把对外地址里的路径变量按同名填进模板，查询串原样带上。 */
    private static String targetPath(ApiResource resource, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String target = resource.targetPath();
        PathPattern pattern = PathPatternParser.defaultInstance.parse(resource.pattern());
        PathPattern.PathMatchInfo match = pattern.matchAndExtract(PathContainer.parsePath(path));
        if (match != null) {
            for (Map.Entry<String, String> variable : match.getUriVariables().entrySet()) {
                target = target.replace("{" + variable.getKey() + "}", variable.getValue());
            }
        }
        return request.getQueryString() == null ? target : target + "?" + request.getQueryString();
    }

    private void write(HttpServletResponse response, int status, ApiResult<?> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
