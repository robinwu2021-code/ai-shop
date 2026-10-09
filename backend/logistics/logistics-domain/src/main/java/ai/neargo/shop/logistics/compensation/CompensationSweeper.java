package ai.neargo.shop.logistics.compensation;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物流补偿（TDD-物流模块 M8）。<b>只处理明确的场景</b>，正常推送中的运单一单都不碰：
 * <ol>
 *   <li>订阅停在 PENDING 超过 10 分钟 —— 总开关刚打开（存量要补订）、或 outbox 重试耗尽；</li>
 *   <li>（批 3 起）订阅成功但 24 小时没有任何推送的在途单 —— 去问微信。</li>
 * </ol>
 * 作业壳在 {@code shop-app/logisticsbridge}：物流模块不依赖 job-api（同进销存的做法）。
 */
@Component
public class CompensationSweeper {

    static final int PENDING_GRACE_MINUTES = 10;

    private final WaybillMapper waybills;
    private final OutboxEventBus events;
    private final LogisticsProperties props;

    public CompensationSweeper(WaybillMapper waybills, OutboxEventBus events, LogisticsProperties props) {
        this.waybills = waybills;
        this.events = events;
        this.props = props;
    }

    public record Result(int pendingRequeued) {
        public String detail() {
            return "补订阅 " + pendingRequeued + " 单";
        }
    }

    @Transactional
    public Result sweep(int limit) {
        if (!props.isSubscribeEnabled()) {
            return new Result(0);   // 开关关着：PENDING 是预期状态，重投只会在 outbox 里堆无用事件
        }
        List<LgsWaybill> pending = waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("sub_state", LgsWaybill.SUB_PENDING)
                .notIn("status", WaybillStatus.DELIVERED, WaybillStatus.CANCELLED)
                .lt("updated_at", LocalDateTime.now().minusMinutes(PENDING_GRACE_MINUTES))
                .orderByAsc("id")
                .last("limit " + Math.max(1, limit)));
        for (LgsWaybill w : pending) {
            events.publish(new WaybillRegistered(w.getShipmentNo()));
        }
        return new Result(pending.size());
    }
}
