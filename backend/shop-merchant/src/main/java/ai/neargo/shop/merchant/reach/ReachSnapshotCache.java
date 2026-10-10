package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.geo.ReachGeoProps;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 门店属性快照的进程内缓存（ADR-034）。
 *
 * <h2>为什么这一份可以缓存</h2>
 * {@code CacheConfig} 的判据是「共享度 × 变更频率」，并明确写着<b>商家覆盖不缓存</b>
 * （按商家分散、命中率低、本人改本人读陈旧会被当场看见）。这一份不在那条里：它是
 * <b>全平台一份、每个消费者请求都读同一个对象</b>（共享度最高），而范围与履约配置是低频变更。
 * 「本人改本人读」那个风险由两件事挡住：写路径 {@code AfterCommit} 主动失效（同实例立即生效）、
 * 商家自己看自己的范围走 {@code StoreReachLoader.load/loadEach} 不经这里。
 *
 * <h2>为什么不进 Spring Cache / ehcache</h2>
 * 这是<b>单键</b>快照，不需要 key 化的缓存容器，{@code @Cacheable} 在这儿是重武器；
 * 而且 {@code CacheConfig} 是共享文件，为一个单键加两处注册不划算。
 * TTL 只是兜底（失效链路万一漏了，最坏错 {@code shop.reach.snapshot-ttl-seconds} 秒），
 * 与那边「TTL 是兜底不是主策略」同一个取舍。
 *
 * <p>⚠️ 单实例前提同 {@code CacheConfig}：生产是 scp jar + systemd 的单实例。
 * 多实例那天失效要跨进程广播，入口就是这里的 {@link #evict()}。
 */
@Component
public class ReachSnapshotCache {

    private final ReachSnapshotLoader loader;
    private final ReachGeoProps props;

    private final AtomicReference<ReachSnapshot> snapshot = new AtomicReference<>();
    private final AtomicLong expireAtMs = new AtomicLong(0);
    /** 对照量：测试据此断言「两次 get 只装一次」「evict 后重装」 */
    private final AtomicLong loadCount = new AtomicLong(0);

    public ReachSnapshotCache(ReachSnapshotLoader loader, ReachGeoProps props) {
        this.loader = loader;
        this.props = props;
    }

    public ReachSnapshot get() {
        ReachSnapshot cur = snapshot.get();
        if (cur != null && System.currentTimeMillis() < expireAtMs.get()) {
            return cur;
        }
        /*
         * 不加锁：并发时最坏重复装几次（纯读、幂等），比让所有请求排在一把锁后面划算。
         * 装完才发布，所以读到的永远是一份完整快照，不会是半份。
         */
        ReachSnapshot fresh = loader.load();
        loadCount.incrementAndGet();
        snapshot.set(fresh);
        expireAtMs.set(System.currentTimeMillis() + props.getSnapshotTtlSeconds() * 1000L);
        return fresh;
    }

    /** 范围 / 履约路 / 门店状态写完后调（{@code AfterCommit} 里），下一次 {@link #get()} 重装 */
    public void evict() {
        expireAtMs.set(0);
    }

    /** 装载次数，仅供测试断言缓存真的在缓存 */
    public long loadCount() {
        return loadCount.get();
    }
}
