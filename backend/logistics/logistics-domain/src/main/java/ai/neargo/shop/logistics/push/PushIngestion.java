package ai.neargo.shop.logistics.push;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.domain.WaybillStatus.Transition;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.LogisticsEvents;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 渠道推来的轨迹入库（TDD-物流模块 M4，AC3 / AC4）。
 *
 * <p>状态只经 {@link WaybillStatus#advance} 推进，签收不会被退回；节点按「时刻 + 原文」去重 ——
 * 快递100 每次推<b>全量</b>轨迹。首次揽收 / 派件 / 异常 / 签收各发一次事件，与状态变更同一事务写入 outbox。
 */
@Component
public class PushIngestion {

    private static final Logger log = LoggerFactory.getLogger(PushIngestion.class);

    /** 每个渠道上线后的第一条真推送原文整条落日志 —— 文档与真实报文对不上时，它就是改解析的依据 */
    private final Set<String> firstSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public enum Outcome { ACCEPTED, REJECTED, UNKNOWN_WAYBILL, IGNORED }

    private final WaybillMapper waybills;
    private final WaybillNodeMapper nodes;
    private final ChannelRouter router;
    private final OutboxEventBus events;
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PushIngestion(WaybillMapper waybills, WaybillNodeMapper nodes, ChannelRouter router, OutboxEventBus events) {
        this(waybills, nodes, router, events, System::currentTimeMillis);
    }

    PushIngestion(WaybillMapper waybills, WaybillNodeMapper nodes, ChannelRouter router, OutboxEventBus events,
                  LongSupplier clock) {
        this.waybills = waybills;
        this.nodes = nodes;
        this.router = router;
        this.events = events;
        this.clock = clock;
    }

    /** 第一条推送原文（截断）。只在每个渠道第一次调用时打 */
    public void logFirst(String channel, String raw) {
        if (firstSeen.add(channel)) {
            log.info("[lgs-push-first] {} 第一条推送原文：{}", channel,
                    raw == null ? "" : (raw.length() > 2000 ? raw.substring(0, 2000) + "…" : raw));
        }
    }

    @Transactional
    public Outcome ingest(String channel, PushReceiver.Parsed p) {
        if (!p.verified()) {
            log.warn("[lgs-push] {} 验签失败，丢弃", channel);
            return Outcome.REJECTED;
        }
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("waybill_no", p.waybillNo()).eq("sub_channel", channel)
                .ne("status", WaybillStatus.CANCELLED).orderByDesc("id").last("limit 1"));
        if (w == null) {
            log.warn("[lgs-push] {} 推来的单号 {} 找不到（或不是在这家订阅的），丢弃", channel, p.waybillNo());
            return Outcome.UNKNOWN_WAYBILL;
        }
        long now = clock.getAsLong();
        LgsWaybill patch = LgsWaybill.patch(w.getId());
        AtomicBoolean dirty = new AtomicBoolean(false);

        if (p.correctedFrom() != null) {
            router.carrierOf(channel, p.channelCarrierCode()).filter(c -> !c.equals(w.getCarrier())).ifPresent(c -> {
                log.info("[lgs-push] {} 渠道把承运商从 {} 纠正为 {}", w.getShipmentNo(), w.getCarrier(), c);
                patch.setCarrierCorrectedFrom(w.getCarrier());
                patch.setCarrier(c);
                dirty.set(true);
            });
        }
        if (p.trackingEnded() && !LgsWaybill.SUB_ENDED.equals(w.getSubState())) {
            patch.setSubState(LgsWaybill.SUB_ENDED);
            dirty.set(true);
        }
        Transition t = null;
        TraceResult trace = p.trace();
        if (trace != null) {
            int added = appendNodes(w.getShipmentNo(), trace.nodes(), channel, "PUSH");
            t = WaybillStatus.advance(w.getStatus(), trace.status());
            if (t.changed()) {
                patch.setStatus(t.next());
            }
            int locker = trace.atLocker() ? 1 : 0;
            if (w.getAtLocker() == null || w.getAtLocker() != locker) {
                patch.setAtLocker(locker);
                dirty.set(true);
            }
            if (added > 0 || t.changed()) {
                patch.setLastEventAt(now);
                dirty.set(true);
            }
            if (t.firstPickedUp()) {
                patch.setPickedUpAt(firstAt(trace, TraceStatus.PICKED, now));
            }
            if (t.firstDelivered()) {
                patch.setSignedAt(signedAt(trace, now));
            }
        }
        if (dirty.get() || (t != null && t.changed())) {
            waybills.updateById(patch);
        }
        if (t != null && t.firstDelivered()) {
            // 手机号用完就删：签收之后再没有任何地方要用完整号码
            waybills.update(null, Wrappers.<LgsWaybill>update().set("receiver_phone_enc", null).eq("id", w.getId()));
        }
        if (t != null && t.changed()) {
            publish(w, t, trace, patch, channel, now);
        }
        return Outcome.ACCEPTED;
    }

    private void publish(LgsWaybill w, Transition t, TraceResult trace, LgsWaybill patch, String channel, long now) {
        if (t.firstPickedUp()) {
            events.publish(new LogisticsEvents.WaybillProgressed(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    WaybillStatus.PICKED_UP, false, now));
        }
        if (WaybillStatus.DELIVERING.equals(t.next()) || WaybillStatus.EXCEPTION.equals(t.next())) {
            events.publish(new LogisticsEvents.WaybillProgressed(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    t.next(), trace.atLocker(), now));
        }
        if (t.firstDelivered()) {
            events.publish(new LogisticsEvents.WaybillSigned(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    patch.getSignedAt(), channel));
        }
    }

    /** 追加节点，按「时刻 + 原文」去重。返回新增条数 */
    int appendNodes(String shipmentNo, List<TraceResult.TraceNode> incoming, String channel, String mode) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        Set<String> have = new HashSet<>();
        for (LgsWaybillNode n : nodes.selectList(Wrappers.<LgsWaybillNode>query().eq("shipment_no", shipmentNo))) {
            have.add(n.getAt() + "|" + n.getText());
        }
        int added = 0;
        for (TraceResult.TraceNode n : incoming) {
            String text = n.info() == null ? "" : n.info().trim();
            if (text.isEmpty() || !have.add(n.at() + "|" + text)) {
                continue;
            }
            LgsWaybillNode row = new LgsWaybillNode();
            row.setShipmentNo(shipmentNo);
            row.setAt(n.at());
            row.setText(text.length() > 255 ? text.substring(0, 255) : text);
            row.setLocation(n.location());
            row.setLatE6(n.latE6());
            row.setLngE6(n.lngE6());
            row.setStatusCode(n.statusCode());
            row.setChannel(channel);
            row.setMode(mode);
            row.setTenantNo("MAIN");
            row.setCreatedAt(LocalDateTime.now());
            nodes.insert(row);
            added++;
        }
        return added;
    }

    /**
     * 签收时间取渠道签收节点的时间；没有或晚于此刻就用此刻 —— 宁晚勿早：
     * 微信确认收货提醒要求签收时间晚于发货时间，早了是 10060029。
     */
    static long signedAt(TraceResult trace, long now) {
        return trace.nodes().stream().filter(n -> n.status() == TraceStatus.SIGNED && n.at() > 0 && n.at() <= now)
                .mapToLong(TraceResult.TraceNode::at).max().orElse(now);
    }

    private static long firstAt(TraceResult trace, TraceStatus s, long now) {
        return trace.nodes().stream().filter(n -> n.status() == s && n.at() > 0 && n.at() <= now)
                .mapToLong(TraceResult.TraceNode::at).min().orElse(now);
    }
}
