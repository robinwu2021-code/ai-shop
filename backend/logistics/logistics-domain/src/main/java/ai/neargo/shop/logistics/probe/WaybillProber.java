package ai.neargo.shop.logistics.probe;

import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.domain.WaybillUpdateAudit;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.TraceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;

/**
 * 按一个探测渠道问一次运单、把答案应用到运单上 —— 物流页读时校正（M7）与补偿作业（M8）<b>共用这一份</b>。
 *
 * <p>问渠道失败不抛：读路径上宁可这次不校正；作业里一单失败不能拖垮一整轮。
 * 问微信时顺手写 {@code wx_status_checked_at}：它既是物流页 10 分钟的闸，也是作业 6 小时的节流。
 *
 * <p>调用方负责事务（同 {@link WaybillProgress}）。
 */
@Component
public class WaybillProber {

    private static final Logger log = LoggerFactory.getLogger(WaybillProber.class);
    public static final String WX = "wx";

    private final WaybillMapper waybills;
    private final ChannelRouter router;
    private final WaybillProgress progress;
    private final PhoneCipher phones;
    private WaybillUpdateAudit audit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAudit(WaybillUpdateAudit audit) { this.audit = audit; }

    public WaybillProber(WaybillMapper waybills, ChannelRouter router, WaybillProgress progress, PhoneCipher phones) {
        this.waybills = waybills;
        this.router = router;
        this.progress = progress;
        this.phones = phones;
    }

    /**
     * @param answered 渠道给了答案（不论有没有新进展）
     * @param changed  答案带来了新进展（状态前进）
     */
    public record Outcome(boolean answered, boolean changed) {
    }

    public Outcome probe(LgsWaybill w, StatusProbe p, long now) {
        LgsWaybill patch = LgsWaybill.patch(w.getId());
        if (WX.equals(p.channel())) {
            patch.setWxStatusCheckedAt(now);
            w.setWxStatusCheckedAt(now);
        }
        Optional<TraceResult> result;
        try {
            result = p.probe(new StatusProbe.ProbeCmd(w.getCarrier(),
                    router.codeOf(w.getCarrier(), p).orElse(null), w.getWaybillNo(),
                    phones.decrypt(w.getReceiverPhoneEnc()), w.getDisplayToken()));
        } catch (RuntimeException e) {
            log.warn("[lgs-probe] {} 问 {} 失败：{}", w.getShipmentNo(), p.channel(), e.toString());
            if (audit != null) audit.record(w, p.channel(), "QUERY", w.getStatus(), w.getStatus(), 0,
                    "ERROR", e.getClass().getSimpleName(), now);
            if (patch.getWxStatusCheckedAt() != null) {
                waybills.update(patch, WaybillSourceIdentity.unchanged(w));
            }
            return new Outcome(false, false);
        }
        if (result.isPresent()) {
            return new Outcome(true, progress.apply(w, result.get(), p.channel(), "QUERY", patch, now).changed());
        }
        if (audit != null) audit.record(w, p.channel(), "QUERY", w.getStatus(), w.getStatus(), 0,
                "NO_RESULT", "渠道未返回可用物流状态", now);
        if (patch.getWxStatusCheckedAt() != null) {
            waybills.update(patch, WaybillSourceIdentity.unchanged(w));
        }
        return new Outcome(false, false);
    }
}
