package ai.neargo.shop.logistics.compensation;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.probe.WaybillProber;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 物流补偿（TDD-物流模块 M8）。<b>只处理明确的场景</b>，正常推送中的运单一单都不碰：
 * <ol>
 *   <li>订阅停在 PENDING 超过 10 分钟 —— 总开关刚打开（存量要补订）、或 outbox 重试耗尽；</li>
 *   <li>推送沉默：订阅成功（DONE / ENDED）但 24 小时没有任何进展的在途单 —— 按探测链问一次，
 *       同一单 6 小时内只问一次（节流列就是 {@code wx_status_checked_at}）。</li>
 * </ol>
 * 作业壳在 {@code shop-app/logisticsbridge}：物流模块不依赖 job-api（同进销存的做法）。
 *
 * <p><b>不包一个大事务</b>：一轮最多 200 单、每单一次 2 秒超时的远程调用，包在一个事务里就是一把
 * 拿几分钟的连接。补订与每单探测各自一个事务，一单失败不连累别的。
 */
@Component
public class CompensationSweeper {

    static final int PENDING_GRACE_MINUTES = 10;
    static final long SILENT_MS = 24 * 3600_000L;
    static final long PROBE_THROTTLE_MS = 6 * 3600_000L;
    /** 作业这一路在 {@code probe-surfaces} 里的名字：渠道列了界面而没列它，作业就不用它问（快递100 默认一个都不放行） */
    static final String SURFACE = "JOB";

    private final WaybillMapper waybills;
    private final OutboxEventBus events;
    private final LogisticsProperties props;
    private final ChannelRouter router;
    private final WaybillProber prober;
    private final SysOutboxMapper outbox;
    private final TransactionTemplate tx;
    private final LongSupplier clock;

    @Autowired
    public CompensationSweeper(WaybillMapper waybills, OutboxEventBus events, LogisticsProperties props,
                               ChannelRouter router, WaybillProber prober, SysOutboxMapper outbox,
                               PlatformTransactionManager txm) {
        this(waybills, events, props, router, prober, outbox, new TransactionTemplate(txm), System::currentTimeMillis);
    }

    CompensationSweeper(WaybillMapper waybills, OutboxEventBus events, LogisticsProperties props,
                        ChannelRouter router, WaybillProber prober, SysOutboxMapper outbox,
                        TransactionTemplate tx, LongSupplier clock) {
        this.waybills = waybills;
        this.events = events;
        this.props = props;
        this.router = router;
        this.prober = prober;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param scanned     推送沉默、到了该问的时候的单数
     * @param answered    渠道给了答案的
     * @param advanced    答案带来了新进展的
     * @param unprobeable 没有渠道能问的（线下付款单、还没换到 token、探测链里没有可用渠道）
     * @param stuck       FATAL 的运单 + 物流事件 outbox FAILED 合计 —— <b>长期应为 0</b>，不为 0 就是缺陷或配置问题
     */
    public record Result(int pendingRequeued, int scanned, int answered, int advanced, int unprobeable, long stuck) {
        public String detail() {
            return "补订阅 " + pendingRequeued + " 单；推送沉默：扫描 " + scanned + " / 探测成功 " + answered
                    + " / 推进 " + advanced + " / 无法探测 " + unprobeable + "；FATAL+outbox FAILED 合计 " + stuck;
        }
    }

    public Result sweep(int limit) {
        int n = Math.max(1, limit);
        int requeued = props.isSubscribeEnabled() ? requeuePending(n) : 0;
        long now = clock.getAsLong();
        List<LgsWaybill> silent = silent(now, n);
        int answered = 0;
        int advanced = 0;
        int unprobeable = 0;
        for (LgsWaybill w : silent) {
            Optional<StatusProbe> p = probeFor(w);
            if (p.isEmpty()) {
                unprobeable++;
                continue;
            }
            WaybillProber.Outcome o = tx.execute(s -> prober.probe(w, p.get(), now));
            if (o != null && o.answered()) {
                answered++;
            }
            if (o != null && o.changed()) {
                advanced++;
            }
        }
        return new Result(requeued, silent.size(), answered, advanced, unprobeable, stuck());
    }

    /** 开关关着时不补：PENDING 是预期状态，重投只会在 outbox 里堆无用事件 */
    private int requeuePending(int limit) {
        Integer count = tx.execute(s -> {
            List<LgsWaybill> pending = waybills.selectList(Wrappers.<LgsWaybill>query()
                    .eq("sub_state", LgsWaybill.SUB_PENDING)
                    .notIn("status", WaybillStatus.DELIVERED, WaybillStatus.CANCELLED)
                    .lt("updated_at", LocalDateTime.now().minusMinutes(PENDING_GRACE_MINUTES))
                    .orderByAsc("id")
                    .last("limit " + limit));
            for (LgsWaybill w : pending) {
                events.publish(new WaybillRegistered(w.getShipmentNo()));
            }
            return pending.size();
        });
        return count == null ? 0 : count;
    }

    /**
     * 订阅成功（DONE / ENDED）∧ 未终态 ∧ 24 小时没有任何进展（从没进展过的看登记时间）∧ 6 小时内没问过。
     * 只看「该有推送却没有」的 —— 正常推送中的单 {@code last_event_at} 一直在刷新，进不来。
     */
    List<LgsWaybill> silent(long now, int limit) {
        long quietSince = now - SILENT_MS;
        LocalDateTime quietSinceAt = LocalDateTime.now().minusHours(24);
        return waybills.selectList(Wrappers.<LgsWaybill>query()
                .in("sub_state", LgsWaybill.SUB_DONE, LgsWaybill.SUB_ENDED)
                .notIn("status", WaybillStatus.DELIVERED, WaybillStatus.CANCELLED)
                .and(q -> q.lt("last_event_at", quietSince)
                        .or(r -> r.isNull("last_event_at").lt("created_at", quietSinceAt)))
                .and(q -> q.isNull("wx_status_checked_at").or().lt("wx_status_checked_at", now - PROBE_THROTTLE_MS))
                .orderByAsc("id")
                .last("limit " + limit));
    }

    /** 探测链里第一个这单用得上的：微信要微信支付单 + 已换到 token；别的渠道要在 probe-surfaces 里放行作业 */
    private Optional<StatusProbe> probeFor(LgsWaybill w) {
        for (StatusProbe p : router.probes(w.getStoreNo(), w.getCarrier())) {
            if (WaybillProber.WX.equals(p.channel())) {
                if (LgsWaybill.PROFILE_WX.equals(w.getProfile()) && w.getDisplayToken() != null
                        && !w.getDisplayToken().isBlank()) {
                    return Optional.of(p);
                }
                continue;
            }
            List<String> surfaces = props.getProbeSurfaces().get(p.channel());
            if (surfaces == null || surfaces.contains(SURFACE)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private long stuck() {
        long fatal = waybills.selectCount(Wrappers.<LgsWaybill>query()
                .ne("status", WaybillStatus.CANCELLED)
                .and(q -> q.eq("sub_state", LgsWaybill.SUB_FATAL).or().eq("bind_state", LgsWaybill.BIND_FATAL)));
        long failed = outbox.selectCount(Wrappers.<SysOutbox>query()
                .eq("status", SysOutbox.FAILED).likeRight("event_type", "LGS_"));
        return fatal + failed;
    }
}
