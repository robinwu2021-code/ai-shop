package ai.neargo.shop.elec.svc;

import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 主系统客户端。地址 {@code shop.services.targets.PLATFORM}（生产 http://127.0.0.1:8081，不经 nginx），
 * 令牌 {@code shop.services.internal-token}（两边同值）。
 */
@Configuration
public class MainSystemConfig {

    /** 认令牌在每个要登录的请求路径上（缓存未命中时），读超时给短：主系统慢了宁可 503 也别拖住用户 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    @ConditionalOnMissingBean(MainSystemApi.class)
    MainSystemApi mainSystemApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, MainSystemApi.class, READ_TIMEOUT);
    }
}
