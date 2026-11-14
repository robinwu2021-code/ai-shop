package ai.neargo.shop.svc;

import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * 给发往某一个服务的所有内部调用加一道拦截器（在加令牌之后执行）。
 *
 * <p>声明成 bean 即生效：{@link InternalHttp#client} 每造一个指向 {@code service} 的客户端都会挂上它，
 * 调用点不用改，后加的客户端也不会漏。例：餐饮独立部署时宿主给 FOOD 的每个请求签范围凭证。
 *
 * @param service     {@link ServiceName} 里的常量
 * @param interceptor 拦截器
 */
public record InternalServiceInterceptor(String service, ClientHttpRequestInterceptor interceptor) {
}
