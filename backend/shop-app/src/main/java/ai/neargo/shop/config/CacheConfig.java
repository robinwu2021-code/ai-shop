package ai.neargo.shop.config;

import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Spring Cache 抽象 + ehcache 实现（工单-规格联动与查询性能一期 步骤 5）。
 *
 * <p><b>为什么是这个组合</b>：业务代码只挂 {@code @Cacheable}/{@code @CacheEvict}，
 * 将来换 Redis/Caffeine 只换本类里的 {@link CacheManager} bean，注解一行不动 ——
 * 引抽象的意义就在这。实现选 ehcache 是因为它已经在依赖树里
 * （shop-base，{@code AuthCache} 在用），不引任何新依赖。
 * ⚠️ 沿用依赖时 classifier 必须是 jakarta —— 见 shop-base pom 里那段注释，
 * 选错的报错（NoClassDefFoundError: javax/cache/...）看不出根因。
 *
 * <p><b>不走 JCache 桥</b>（那要多引 cache-api），直接用 ehcache 3 的编程式 API
 * 包一层 —— 与 {@code AuthCache} 同一条路。
 *
 * <p><b>缓存进哪一层的判据是「共享度 × 变更频率」</b>：只缓存平台层
 * （类目树、类目形态）—— 所有人读同一份、运营偶尔动一次。商家覆盖不缓存
 * （按商家分散、命中率低，且本人改本人读，陈旧 30 秒会被当场看见）；
 * 商品不缓存（上下架要实时）。
 *
 * <p><b>30 秒 TTL 是兜底不是主策略</b>：失效靠运营写路径的 {@code @CacheEvict}
 * （宁可 allEntries 失效过度 —— 细粒度失效的每个漏网之鱼都是一个
 * 「运营改了半天不生效」的工单）；TTL 只兜「失效链路万一漏了」，最坏错 30 秒。
 *
 * <p>⚠️ <b>单机缓存的前提：生产是单实例部署</b>（scp jar + systemd）。
 * 多实例那天这里换 RedisCacheManager，失效才能跨实例 —— 到时这条注释就是入口。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 平台类目树。运营改类目时整棵失效 */
    public static final String CATEGORY_TREE = "categoryTree";
    /** categoryNo → 形态（FRESH/NORMAL/...）。建品每次要查，失效与类目树同批 */
    public static final String CATEGORY_TYPE = "categoryType";

    /** ehcache 的管理器单独成 bean，为的是 destroyMethod —— 不关的话热重启泄堆外账本 */
    @Bean(destroyMethod = "close")
    public org.ehcache.CacheManager ehcacheManager() {
        return CacheManagerBuilder.newCacheManagerBuilder()
                .withCache(CATEGORY_TREE, cacheConfig(4))
                .withCache(CATEGORY_TYPE, cacheConfig(2048))
                .build(true);
    }

    private static CacheConfigurationBuilder<Object, Object> cacheConfig(int maxEntries) {
        return CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        Object.class, Object.class,
                        // 只有 heap 一层。这里出现 .disk(...) 就是 bug —— 与 AuthCache 同一条判据：
                        // 这是可丢的加速层，不是要持久化的状态
                        ResourcePoolsBuilder.newResourcePoolsBuilder().heap(maxEntries, EntryUnit.ENTRIES))
                .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofSeconds(30)));
    }

    @Bean
    public CacheManager cacheManager(org.ehcache.CacheManager ehcache) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                new EhcacheSpringCache(CATEGORY_TREE, ehcache.getCache(CATEGORY_TREE, Object.class, Object.class)),
                new EhcacheSpringCache(CATEGORY_TYPE, ehcache.getCache(CATEGORY_TYPE, Object.class, Object.class))));
        return manager;
    }
}
