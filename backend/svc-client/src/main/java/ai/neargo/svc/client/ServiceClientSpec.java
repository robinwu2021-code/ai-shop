package ai.neargo.svc.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 造一个服务客户端需要知道的全部东西。**这个模块本身不认识任何配置键与请求头名**，
 * 都由调用方从这里传进来 —— shop 侧与 job 侧的配置形状不同，而传输规矩相同。
 *
 * @param service        被调服务的名字，只用于寻址与报错
 * @param baseUrl        服务名 → 基址（如 {@code http://pay.svc.internal:8083}）。
 *                       <b>每次调用都会重新问一遍</b>：将来换成服务发现时只换这个函数
 * @param tokenHeader    令牌放在哪个请求头
 * @param token          令牌；每次调用现取
 * @param requireToken   为 {@code true} 时令牌为空直接判 {@link CallOutcome#NOT_CONFIGURED}，请求不发出去。
 *                       <b>「没配就不校验」的表现是内部口对任何人开放，而且没有任何症状</b>
 * @param connectTimeout 连接超时
 * @param readTimeout    读超时（从发出请求到拿到应答）
 * @param addressConfigKey 「地址没配」时报错里点名的配置项，可为 {@code null}。
 *                       <b>报错要说清改哪个配置</b> —— 只说「没配地址」的话，读的人还得先去猜键名
 * @param tokenConfigKey   同上，「令牌没配」时点名的配置项
 */
public record ServiceClientSpec(
        String service,
        Function<String, Optional<String>> baseUrl,
        String tokenHeader,
        Supplier<String> token,
        boolean requireToken,
        Duration connectTimeout,
        Duration readTimeout,
        String addressConfigKey,
        String tokenConfigKey) {

    /** 不点名配置项的写法（报错里只说「没配」） */
    public ServiceClientSpec(String service, Function<String, Optional<String>> baseUrl, String tokenHeader,
                             Supplier<String> token, boolean requireToken, Duration connectTimeout,
                             Duration readTimeout) {
        this(service, baseUrl, tokenHeader, token, requireToken, connectTimeout, readTimeout, null, null);
    }

    public ServiceClientSpec {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(tokenHeader, "tokenHeader");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
    }

    /** 同一个服务、换一个读超时 —— job 侧每个任务的超时各不相同 */
    public ServiceClientSpec withReadTimeout(Duration timeout) {
        return new ServiceClientSpec(service, baseUrl, tokenHeader, token, requireToken, connectTimeout, timeout,
                addressConfigKey, tokenConfigKey);
    }

    /** 报错里点名配置项 */
    public ServiceClientSpec withConfigKeys(String addressKey, String tokenKey) {
        return new ServiceClientSpec(service, baseUrl, tokenHeader, token, requireToken, connectTimeout, readTimeout,
                addressKey, tokenKey);
    }
}
