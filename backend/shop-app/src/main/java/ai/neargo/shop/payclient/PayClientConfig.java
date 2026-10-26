package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PaymentInternalApi;
import ai.neargo.shop.pay.client.port.RemotePaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
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

    /**
     * 支付单的远程实现（TDD-支付服务-I3支付单退款流水 S8）：按调用方鉴权，凭据逐个调用方配
     * （{@code shop.pay.internal-caller-credentials.MALL} / {@code .FOOD}，部署环境给，不进仓库）。
     */
    @Bean
    PaymentPort remotePaymentPort(InternalHttp http, Environment env) {
        Map<String, String> credentials = Binder.get(env)
                .bind("shop.pay.internal-caller-credentials", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        return new RemotePaymentPort(http.client(ServiceName.PAY, PaymentInternalApi.class, PAY_READ_TIMEOUT),
                credentials, ServiceName.PAY);
    }
}
