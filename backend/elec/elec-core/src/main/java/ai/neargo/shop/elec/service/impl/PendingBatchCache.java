package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.expiry.ExpiryPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 待确认的表放在这里。两条硬规矩：
 *
 * <ul>
 *   <li><b>到期是绝对时刻</b>：上传起最多 {@code pendingTtlMinutes}。访问不续期；从原件重建出来的条目
 *       沿用原来的到期时刻 —— 否则重建一次续一小时，数据实际能在内存里活好几个小时</li>
 *   <li><b>条数封顶</b>（elec-svc 堆只有 768MB，一张两万行约 12MB）。挤出无害：下次访问从原件重建</li>
 * </ul>
 *
 * <p>「过没过期」<b>不以缓存为准</b>：调用方先按库里批次的状态与创建时刻判定，再来这里取。缓存只是加速。
 * 用 Ehcache 而不是自己写 Map + 定时器，是因为过期清理与容量淘汰由它负责，没有需要人去关的线程。
 */
@Slf4j
@ConditionalOnElec
@Component
public class PendingBatchCache {

    private static final String NAME = "elec-pending-batch";

    private final CacheManager manager;
    private final Cache<String, PendingBatch> cache;
    private final LongAdder rebuilds = new LongAdder();

    @Autowired
    public PendingBatchCache(ElecProperties props) {
        this(props.getUpload().getCacheMaxBatches());
    }

    PendingBatchCache(int maxBatches) {
        this.manager = CacheManagerBuilder.newCacheManagerBuilder()
                .withCache(NAME, CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class,
                                PendingBatch.class,
                                // 只有 heap 一层：值是按引用放的，不序列化
                                ResourcePoolsBuilder.newResourcePoolsBuilder().heap(Math.max(1, maxBatches),
                                        EntryUnit.ENTRIES))
                        .withExpiry(new ByDeadline()))
                .build(true);
        this.cache = manager.getCache(NAME, String.class, PendingBatch.class);
    }

    public void put(PendingBatch b) {
        if (Instant.now().isBefore(b.deadline())) {
            cache.put(b.batchNo(), b);
        }
    }

    /** 缓存里没有（被挤出、服务重启过）就用 {@code rebuild} 从原件重建并放回。rebuild 可以回 null（原件不在） */
    public PendingBatch getOrRebuild(String batchNo, Supplier<PendingBatch> rebuild) {
        PendingBatch b = cache.get(batchNo);
        if (b != null) {
            return b;
        }
        rebuilds.increment();
        b = rebuild.get();
        if (b != null) {
            put(b);
            log.info("待确认批次 {} 从原件重建（累计 {} 次；频繁就调大 cache-max-batches）", batchNo, rebuilds.sum());
        }
        return b;
    }

    /** 内存里有没有（不触发重建） */
    public boolean contains(String batchNo) {
        return cache.containsKey(batchNo);
    }

    PendingBatch peek(String batchNo) {
        return cache.get(batchNo);
    }

    public void evict(String batchNo) {
        cache.remove(batchNo);
    }

    @PreDestroy
    public void close() {
        manager.close();
    }

    /** 按值里的到期时刻算剩余寿命；访问与更新都不改它 */
    static final class ByDeadline implements ExpiryPolicy<String, PendingBatch> {

        @Override
        public Duration getExpiryForCreation(String key, PendingBatch value) {
            Duration left = Duration.between(Instant.now(), value.deadline());
            return left.isNegative() ? Duration.ZERO : left;
        }

        @Override
        public Duration getExpiryForAccess(String key, Supplier<? extends PendingBatch> value) {
            return null;
        }

        @Override
        public Duration getExpiryForUpdate(String key, Supplier<? extends PendingBatch> oldValue,
                                           PendingBatch newValue) {
            return getExpiryForCreation(key, newValue);
        }
    }
}
