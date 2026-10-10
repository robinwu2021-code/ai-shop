package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.merchant.mapper.ReachMatchMapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 门店属性快照的进程内缓存（ADR-034）。
 *
 * <h2>失效靠版本探针，不靠人工接线</h2>
 * 每次 {@link #get()} 先用一条很轻的聚合 SQL 问「影响判定的那五张表变了没有」
 * （{@link ReachMatchMapper#snapshotVersion()}：每张表的 {@code COUNT(*)} 与 {@code SUM(version)} 拼成一个串，
 * 对 insert / update / 物理删都敏感、与时间精度无关）。变了就重装，没变就复用。
 *
 * <p><b>为什么不在写入口 evict</b>：影响快照的写路径散在 6 个 service 的二十多个事务方法里
 * （建店、改范围、改履约、停店、主体停用、自营建店、运营治理…），手工接必漏一处，
 * 而漏接的症状是「商家改完范围、买家几十秒内看不到」且不报错。探针让正确性由机器保证，
 * 将来新增写路径不需要任何人记得这件事。
 *
 * <p><b>探针跳过窗口</b>（{@code shop.reach.snapshot-probe-skip-ms}，默认 500ms）：
 * 一次目录请求里会对几十家店反复问快照，窗口内直接复用、连探针都省。
 * 窗口越大越省、「改完多久生效」的上限也越大 —— 500ms 对商家感知是即时的。
 *
 * <h2>与 CacheConfig 的判据不冲突</h2>
 * 那边写着<b>商家覆盖不缓存</b>（按商家分散、命中率低）。这一份不在那条里：它是
 * <b>全平台一份、每个消费者请求都读同一个对象</b>（共享度最高），而范围与履约配置是低频变更。
 */
@Component
public class ReachSnapshotCache {

    private final ReachSnapshotLoader loader;
    private final ReachMatchMapper mapper;
    private final ReachGeoProps props;

    private final AtomicReference<ReachSnapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<String> cachedVersion = new AtomicReference<>(null);
    private final AtomicLong probeSkipUntilMs = new AtomicLong(0);
    /** 对照量：测试据此断言「复用时没有重装」「数据变了会重装」 */
    private final AtomicLong loadCount = new AtomicLong(0);

    public ReachSnapshotCache(ReachSnapshotLoader loader, ReachMatchMapper mapper, ReachGeoProps props) {
        this.loader = loader;
        this.mapper = mapper;
        this.props = props;
    }

    public ReachSnapshot get() {
        long now = System.currentTimeMillis();
        ReachSnapshot cur = snapshot.get();
        if (cur != null && now < probeSkipUntilMs.get()) {
            return cur;
        }
        String version = DataScopeContext.executeWithoutScope(mapper::snapshotVersion);
        if (cur != null && version != null && version.equals(cachedVersion.get())) {
            probeSkipUntilMs.set(now + props.getSnapshotProbeSkipMs());
            return cur;
        }
        /*
         * 不加锁：并发时最坏重复装几次（纯读、幂等），比让所有请求排在一把锁后面划算。
         * 装完才发布，所以读到的永远是一份完整快照，不会是半份。
         */
        ReachSnapshot fresh = loader.load();
        loadCount.incrementAndGet();
        snapshot.set(fresh);
        cachedVersion.set(version);
        probeSkipUntilMs.set(now + props.getSnapshotProbeSkipMs());
        return fresh;
    }

    /**
     * 立刻作废（下一次 {@link #get()} 必定重探针并重装）。
     *
     * <p>正常不需要调它 —— 版本探针已经覆盖了数据变更。留着给两种场合：
     * 测试要确定性地重装；将来真出现「探针测不到的变更」时有一个显式出口。
     */
    public void evict() {
        probeSkipUntilMs.set(0);
        cachedVersion.set(null);
    }

    /** 装载次数，仅供测试断言缓存真的在复用 */
    public long loadCount() {
        return loadCount.get();
    }
}
