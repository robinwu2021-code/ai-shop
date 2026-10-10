package ai.neargo.shop.logistics.push;

import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 渠道推来的轨迹入库（TDD-物流模块 M4，AC3 / AC4）。
 *
 * <p>本类只管「这条推送属于哪张运单、渠道说了什么额外的事（纠正承运商、停止跟踪）」；
 * 节点、状态、事件、清手机号、判换 token 都在 {@link WaybillProgress} —— 与读时校正、补偿作业同一份规矩。
 */
@Component
public class PushIngestion {

    private static final Logger log = LoggerFactory.getLogger(PushIngestion.class);

    /** 每个渠道上线后的第一条<b>验签通过的</b>真推送原文整条落日志 —— 文档与真实报文对不上时，它就是改解析的依据 */
    private final Set<String> firstSeen = ConcurrentHashMap.newKeySet();

    public enum Outcome { ACCEPTED, REJECTED, UNKNOWN_WAYBILL }

    private final WaybillMapper waybills;
    private final ChannelRouter router;
    private final WaybillProgress progress;
    private final LongSupplier clock;

    @Autowired
    public PushIngestion(WaybillMapper waybills, ChannelRouter router, WaybillProgress progress) {
        this(waybills, router, progress, System::currentTimeMillis);
    }

    PushIngestion(WaybillMapper waybills, ChannelRouter router, WaybillProgress progress, LongSupplier clock) {
        this.waybills = waybills;
        this.router = router;
        this.progress = progress;
        this.clock = clock;
    }

    /**
     * 第一条<b>验签通过的</b>推送原文（截断）。2026-10-09 上线时第一条记录被我自己打的探测请求占掉了 ——
     * 所以只在验签通过后才记。
     */
    void logFirst(String channel, String raw) {
        if (firstSeen.add(channel)) {
            log.info("[lgs-push-first] {} 第一条推送原文：{}", channel,
                    raw == null ? "" : (raw.length() > 2000 ? raw.substring(0, 2000) + "…" : raw));
        }
    }

    @Transactional
    public Outcome ingest(String channel, PushReceiver.Parsed p, String raw) {
        if (!p.verified()) {
            log.warn("[lgs-push] {} 验签失败，丢弃", channel);
            return Outcome.REJECTED;
        }
        logFirst(channel, raw);
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("waybill_no", p.waybillNo()).eq("sub_channel", channel)
                .ne("status", WaybillStatus.CANCELLED).orderByDesc("id").last("limit 1"));
        if (w == null) {
            log.warn("[lgs-push] {} 推来的单号 {} 找不到（或不是在这家订阅的），丢弃", channel, p.waybillNo());
            return Outcome.UNKNOWN_WAYBILL;
        }
        LgsWaybill patch = LgsWaybill.patch(w.getId());
        if (p.correctedFrom() != null) {
            router.carrierOf(channel, p.channelCarrierCode()).filter(c -> !c.equals(w.getCarrier())).ifPresent(c -> {
                log.info("[lgs-push] {} 渠道把承运商从 {} 纠正为 {}", w.getShipmentNo(), w.getCarrier(), c);
                patch.setCarrierCorrectedFrom(w.getCarrier());
                patch.setCarrier(c);
            });
        }
        if (p.trackingEnded() && !LgsWaybill.SUB_ENDED.equals(w.getSubState())) {
            patch.setSubState(LgsWaybill.SUB_ENDED);
        }
        progress.apply(w, p.trace(), channel, "PUSH", patch, clock.getAsLong());
        return Outcome.ACCEPTED;
    }
}
