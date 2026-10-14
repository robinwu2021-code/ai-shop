package ai.neargo.auth.store;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/**
 * 鉴权链路上的本地缓存。**堆内，绝不落盘。**
 *
 * <h2>为什么不落盘</h2>
 * Ehcache 的持久化目录在非正常关闭后会被**整个删掉**（日志里只有一行
 * "Probably unclean shutdown … deleted root directory"）。2026-08-24 线上重现过一次，
 * 而会话正存在那里 —— 表现是<b>全员掉线</b>。
 *
 * <p>本类缓存的东西**权威都在库里**，丢了只是回源查一次。
 * 谁要是为了「重启后还热着」给它加上 disk 层，等于把那次事故重新装回来，
 * 而且这次连数据都不是它的 —— 纯亏。
 *
 * <h2>什么能放进来</h2>
 * <b>判据一句话：库里有权威副本吗？</b>有 → 可以放（会话映射、身份）。
 * 没有、它自己就是权威（验证码、重置令牌、限流计数）→ <b>绝对不能放</b>：
 * 本类是<b>进程内</b>的（多实例各算各的）、而且<b>会淘汰</b>（条目打满静默丢），
 * 丢一个验证码就是「用户收到了码但校验不过」。
 *
 * <h2>命中率必须看得见</h2>
 * 条目上限设小了的表现不是报错，而是「查库莫名其妙变多」。
 * 没有 {@link #stats()} 的话，这件事只能靠猜。
 */
public final class AuthCache<K, V> implements AutoCloseable {

    private final CacheManager manager;
    private final Cache<K, V> cache;
    private final String name;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public AuthCache(String name, Class<K> keyType, Class<V> valueType,
                     Duration ttl, int maxEntries) {
        this.name = name;
        this.manager = CacheManagerBuilder.newCacheManagerBuilder()
                .withCache(name, CacheConfigurationBuilder.newCacheConfigurationBuilder(
                                keyType, valueType,
                                // 只有 heap 一层。**这里出现 .disk(...) 就是 bug**，见类注释
                                ResourcePoolsBuilder.newResourcePoolsBuilder()
                                        .heap(maxEntries, EntryUnit.ENTRIES))
                        .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(ttl)))
                .build(true);
        this.cache = manager.getCache(name, keyType, valueType);
    }

    /** 取；没有就返回 null（**不做负缓存**，见 {@code DbTokenStore} 的说明）。 */
    public V get(K key) {
        V v = cache.get(key);
        if (v == null) {
            misses.increment();
        } else {
            hits.increment();
        }
        return v;
    }

    public void put(K key, V value) {
        cache.put(key, value);
    }

    public void evict(K key) {
        cache.remove(key);
    }

    /**
     * 清空。**踢一个人时不要调它** —— 那会让所有在线用户的下一次请求一起回源，
     * 把一次撤销放大成库上的尖峰。撤销走 {@link #evict} 逐条剔。
     */
    public void clear() {
        cache.clear();
    }

    public Stats stats() {
        return new Stats(name, hits.sum(), misses.sum());
    }

    @Override
    public void close() {
        manager.close();
    }

    /**
     * @param hitRate 命中率；**掉下去通常意味着条目上限太小或 TTL 被改短了**，
     *                而两者的症状都只是「查库变多」，不会报错
     */
    public record Stats(String name, long hits, long misses) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 1.0 : (double) hits / total;
        }
    }
}
