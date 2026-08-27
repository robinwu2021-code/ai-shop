package ai.neargo.shop.config;

import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.auth.store.EhcacheTokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.auth.store.RedisTokenStore;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.time.Duration;

/**
 * 会话存储装配，三选一（{@code shop.auth.token-store}）：
 *
 * <table border="1">
 *   <caption>三种形态的取舍</caption>
 *   <tr><th>值</th><th>活过重启</th><th>多副本共享</th><th>外部依赖</th><th>用在哪</th></tr>
 *   <tr><td>{@code memory}</td><td>否</td><td>否</td><td>无</td><td>测试、临时调试</td></tr>
 *   <tr><td>{@code ehcache}</td><td><b>是</b></td><td>否</td><td>无（本地磁盘）</td><td>本地开发、<b>单实例生产</b></td></tr>
 *   <tr><td>{@code redis}</td><td>是</td><td><b>是</b></td><td>Redis</td><td>多副本生产</td></tr>
 * </table>
 *
 * <p><b>默认值仍是 memory 而不是 ehcache</b>：默认值要对**测试**最友好。
 * ehcache 会在工作目录下建持久化目录并加文件锁，并行跑的测试进程会互相锁住；
 * 而单测本来就该每次从干净状态开始，「上一次跑剩下的会话」是纯粹的干扰。
 * 真正要持久化的场景（本地开发、单机生产）显式配一行即可。
 *
 * <p><b>ehcache 与 redis 的分界线只有一条：部署几个副本。</b>
 * 一个 → ehcache 足够且省一个中间件；两个及以上 → 必须 redis，
 * 否则同一个人被负载均衡打到另一个实例上就是未登录，
 * 而这个症状是**间歇性的**，最难查。
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

    /**
     * 单机持久化会话。
     *
     * <p>返回类型写成具体的 {@link EhcacheTokenStore} 而不是 {@link TokenStore}：
     * Spring 要看得见它实现了 {@code AutoCloseable} 才会在关机时调 {@code close()}，
     * 而 <b>Ehcache 不正常关闭就会在下次启动丢弃整个持久化目录</b> ——
     * 声明成接口类型的话，持久化会静默失效，症状是「配了 ehcache 但重启照样掉线」。
     *
     * @param dir    持久化目录，每个实例独占（Ehcache 对它加文件锁）
     * @param diskMb 磁盘上限，写满按 LRU 淘汰
     */
    @Bean
    @ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "ehcache")
    EhcacheTokenStore ehcacheTokenStore(
            ObjectMapper mapper,
            @Value("${shop.auth.ehcache.dir:./data/sessions}") String dir,
            @Value("${shop.auth.ehcache.disk-mb:64}") int diskMb) {
        return new EhcacheTokenStore(mapper, new File(dir), diskMb, TTL);
    }

    @Bean
    @ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "redis")
    TokenStore redisTokenStore(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisTokenStore(redis, mapper, TTL);
    }

    /**
     * 单池形态（memory / ehcache / redis）下的 {@link ai.neargo.shop.auth.TokenStores}：
     * 三端共用一个存储，取哪个池都返回它。
     *
     * <p>{@code db} 形态由 {@code DbSessionConfig} 装配 {@code RealmRoutingTokenStore}
     * ——它自己就实现了 {@code TokenStores}，所以这里用
     * {@code @ConditionalOnMissingBean} 让位。
     */
    @Bean
    @ConditionalOnMissingBean(TokenStores.class)
    TokenStores tokenStores(TokenStore tokenStore) {
        return TokenStores.single(tokenStore);
    }
}
