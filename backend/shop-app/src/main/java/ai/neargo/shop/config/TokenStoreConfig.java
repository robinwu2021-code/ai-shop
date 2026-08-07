package ai.neargo.shop.config;

import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.auth.store.RedisTokenStore;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 会话存储装配。默认 memory（本地开发零依赖启动）；生产配 {@code shop.auth.token-store=redis}。
 *
 * <p>默认值刻意选 memory 而不是 redis：本地起服务不该先起一个 Redis。
 * 误配的风险由部署清单与启动日志兜底（memory 模式启动时打 WARN）。
 */
@Configuration
public class TokenStoreConfig {

    /** 30 天：与 C 端「一次登录长期有效」的体感一致；运营端由前端主动登出控制。 */
    private static final Duration TTL = Duration.ofDays(30);

    @Bean
    @ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "memory", matchIfMissing = true)
    TokenStore memoryTokenStore() {
        return new MemoryTokenStore(TTL);
    }

    @Bean
    @ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "redis")
    TokenStore redisTokenStore(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisTokenStore(redis, mapper, TTL);
    }
}
