package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付域独立形态下的远程客户端。**与两个 {@code Remote*AppService} 同一个开关**：
 * {@code shop.pay.deployment=embedded}（今天的生产形态）时这个 Bean 不存在，本地实现直接调领域服务。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class PayClientConfig {

    /** 与迁移前一致：pay 的内部调用读超时 5 秒 */
    private static final Duration PAY_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    PayInternalApi payInternalApi(InternalHttp http) {
        return http.client(ServiceName.PAY, PayInternalApi.class, PAY_READ_TIMEOUT);
    }
}
