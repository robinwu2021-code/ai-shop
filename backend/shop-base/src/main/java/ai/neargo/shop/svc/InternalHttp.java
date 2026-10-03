package ai.neargo.shop.svc;

import ai.neargo.svc.client.ServiceClientSpec;
import ai.neargo.svc.client.ServiceClients;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * shop 侧的服务间调用：把 svc-client 接到本仓库的配置上 —— <b>只管「怎么调」，不管「调哪里」</b>。
 *
 * <ul>
 *   <li>地址：{@link ServiceLocator}，每次调用现查（今天读 {@code shop.services.targets.*}）；</li>
 *   <li>令牌：{@code shop.services.internal-token}，放在 {@link #TOKEN_HEADER}；
 *       <b>没配就一律拒绝</b>，不是「没配就不校验」—— 后者的表现是内部口对任何人开放，而且没有任何症状；</li>
 *   <li>HTTP/1.1、不记 body、五种失败分类：由 svc-client 统一执行。</li>
 * </ul>
 *
 * <p>内部调用<b>不经 nginx</b>，走服务名解析到的本机地址（见 ADR-023）。
 *
 * <p>用法：先用 {@link #client} 造一个 {@code @HttpExchange} 接口的代理（通常做成 Bean），
 * 调用时包一层 {@code ServiceCalls.call(ServiceName.X, () -> api.xxx())}。
 */
@Component
public class InternalHttp {

    /** 进程之间的共享密钥放在这个请求头里。服务端校验时引用同一个常量 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final ServiceLocator locator;
    private final String token;

    public InternalHttp(ServiceLocator locator, @Value("${shop.services.internal-token:}") String token) {
        this.locator = locator;
        this.token = token == null ? "" : token;
    }

    /**
     * @param service     {@link ServiceName} 里的常量
     * @param readTimeout 读超时，按服务给
     */
    public <T> T client(String service, Class<T> api, Duration readTimeout) {
        return ServiceClients.create(api, new ServiceClientSpec(
                service, locator::baseUrlOf, TOKEN_HEADER, () -> token, true, CONNECT_TIMEOUT, readTimeout)
                .withConfigKeys("shop.services.targets." + service, "shop.services.internal-token"));
    }
}
