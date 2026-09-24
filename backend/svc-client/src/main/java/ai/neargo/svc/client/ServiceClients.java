package ai.neargo.svc.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * 按 {@link ServiceClientSpec} 造一个 {@code @HttpExchange} 接口的代理。
 *
 * <h2>四条规矩（原来散在两个手写客户端里，现在只在这里写一次）</h2>
 * <ol>
 *   <li><b>HTTP/1.1</b>：JDK HttpClient 在 HTTP/2 下发大 body 会挂住，
 *       某些服务端对 HTTP/2 的大 body 直接回 400 而错误信息完全不指向协议 —— 本仓库踩过；</li>
 *   <li><b>逻辑地址，每次现查</b>：代理里的基址是个占位，真正的地址由拦截器在发请求那一刻
 *       向 {@link ServiceClientSpec#baseUrl()} 要。于是「没配地址」在<b>调用时</b>报，
 *       而不是启动失败；将来换服务发现，也只换那一个函数；</li>
 *   <li><b>令牌头由调用方给名字</b>：shop 侧是 {@code X-Internal-Token}，job 侧是 {@code X-Job-Token}；</li>
 *   <li><b>非 2xx 一律 {@link CallOutcome#REMOTE_ERROR}</b>，异常里带状态码、不带 body。</li>
 * </ol>
 *
 * <p>用法：{@code ServiceCalls.call(service, () -> api.xxx())}，由 {@link ServiceCalls}
 * 把传输层的异常归到五种结局里。
 */
public final class ServiceClients {

    /** 占位地址：拦截器会把它整个换掉。选一个永远解析不到的保留域名，漏换时连不上而不是连错 */
    static final String PLACEHOLDER_BASE = "http://unresolved.invalid";

    private ServiceClients() {
    }

    public static <T> T create(Class<T> api, ServiceClientSpec spec) {
        HttpClient jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(spec.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(spec.readTimeout());

        RestClient rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(PLACEHOLDER_BASE)
                // 顺序有意义：先确定地址（没配就不必再看令牌），再加令牌
                .requestInterceptor(new ResolveBaseUrl(spec))
                .requestInterceptor(new AttachToken(spec))
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), (req, res) -> {
                    int code = res.getStatusCode().value();
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, spec.service(), code,
                            spec.service() + " 返回 " + code);
                })
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(api);
    }

    /** 占位地址 → 这一刻 {@code baseUrl} 给出的真实地址。保留路径与查询串 */
    static final class ResolveBaseUrl implements ClientHttpRequestInterceptor {
        private final ServiceClientSpec spec;

        ResolveBaseUrl(ServiceClientSpec spec) {
            this.spec = spec;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            String base = spec.baseUrl().apply(spec.service())
                    .filter(b -> !b.isBlank())
                    .orElseThrow(() -> new ServiceCallException(CallOutcome.NOT_CONFIGURED, spec.service(), 0,
                            "没有配置服务 " + spec.service() + " 的地址"));
            // 尾斜杠统一去掉：路径一律以 / 开头，两边都留会拼出 //internal，有些反代下会 404
            String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            URI original = request.getURI();
            String query = original.getRawQuery() == null ? "" : "?" + original.getRawQuery();
            URI target = URI.create(trimmed + original.getRawPath() + query);
            return execution.execute(new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                    return target;
                }
            }, body);
        }
    }

    static final class AttachToken implements ClientHttpRequestInterceptor {
        private final ServiceClientSpec spec;

        AttachToken(ServiceClientSpec spec) {
            this.spec = spec;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            String token = spec.token().get();
            if (token == null || token.isBlank()) {
                if (spec.requireToken()) {
                    throw new ServiceCallException(CallOutcome.NOT_CONFIGURED, spec.service(), 0,
                            "调用 " + spec.service() + " 的令牌没配 —— 内部调用一律拒绝");
                }
                token = "";
            }
            request.getHeaders().set(spec.tokenHeader(), token);
            return execution.execute(request, body);
        }
    }
}
