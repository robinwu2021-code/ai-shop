package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.WaybillStatus.Transition;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.wxbind.WxBindPolicy;
import ai.neargo.shop.logistics.wxbind.WxBindRequested;
import ai.neargo.shop.spi.logistics.LogisticsEvents;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把一次轨迹（推送来的、或主动查来的）应用到运单上 —— 推送入库、物流页读时校正、补偿作业三处<b>共用这一份</b>，
 * 所以「状态只进不退、签收只写一次、事件只发一次、签收后清手机号、揽收后判换 token」这些规矩只写一遍。
 *
 * <p>调用方负责事务：本类的写与 outbox 事件要和调用方在同一个事务里。
 */
@Component
public class WaybillProgress {

    private final WaybillMapper waybills;
    private final WaybillNodeMapper nodes;
    private final OutboxEventBus events;
    private final LogisticsProperties props;

    public WaybillProgress(WaybillMapper waybills, WaybillNodeMapper nodes, OutboxEventBus events,
                           LogisticsProperties props) {
        this.waybills = waybills;
        this.nodes = nodes;
        this.events = events;
        this.props = props;
    }

    /**
     * @param channel 哪个渠道来的（kuaidi100 / wx …）
     * @param mode    PUSH / QUERY
     * @param patch   调用方已经攒好的补丁（承运商纠正、订阅结束之类），可为空补丁；本方法往里继续写并落库
     * @return 状态推进的结果
     */
    public Transition apply(LgsWaybill w, TraceResult trace, String channel, String mode, LgsWaybill patch, long now) {
        boolean dirty = patch.getCarrier() != null || patch.getSubState() != null || patch.getWxStatusCheckedAt() != null;
        Transition t = WaybillStatus.advance(w.getStatus(), trace == null ? null : trace.status());
        if (trace != null) {
            int added = appendNodes(w.getShipmentNo(), trace.nodes(), channel, mode);
            if (t.changed()) {
                patch.setStatus(t.next());
                dirty = true;
            }
            int locker = trace.atLocker() ? 1 : 0;
            if (w.getAtLocker() == null || w.getAtLocker() != locker) {
                patch.setAtLocker(locker);
                dirty = true;
            }
            if (added > 0 || t.changed()) {
                patch.setLastEventAt(now);
                dirty = true;
            }
            if (t.firstPickedUp()) {
                patch.setPickedUpAt(firstAt(trace, TraceStatus.PICKED, now));
            }
            if (t.firstDelivered()) {
                patch.setSignedAt(signedAt(trace, now));
            }
        }
        if (dirty) {
            waybills.updateById(patch);
        }
        if (t.firstDelivered()) {
            // 手机号用完就删：签收之后再没有任何地方要用完整号码
            waybills.update(null, Wrappers.<LgsWaybill>update().set("receiver_phone_enc", null).eq("id", w.getId()));
        }
        if (!t.changed() && trace != null && trace.atLocker() && !Integer.valueOf(1).equals(w.getAtLocker())
                && WaybillStatus.DELIVERING.equals(w.getStatus())) {
            // 派件中途放进了驿站 / 快递柜：主状态没变，但「去取件」是买家这一路上最要紧的一句
            events.publish(new LogisticsEvents.WaybillProgressed(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    carrierOf(w, patch), w.getWaybillNo(), WaybillStatus.DELIVERING, true, now));
        }
        if (t.changed()) {
            publish(w, carrierOf(w, patch), t, trace,
                    patch.getSignedAt() == null ? now : patch.getSignedAt(), channel, now);
            // 进了揽收（或更后）之后再判一次换 token —— 上次 9300559 的，这一次自然重试
            LgsWaybill after = copyForPolicy(w, t.next());
            if (WxBindPolicy.ready(after, props.getWxBind().onShip())) {
                events.publish(new WxBindRequested(w.getShipmentNo()));
            }
        } else if (trace != null && WxBindPolicy.ready(w, props.getWxBind().onShip())) {
            // 状态没变但又来了一条推送：上次微信还没收录的，再试一次
            events.publish(new WxBindRequested(w.getShipmentNo()));
        }
        return t;
    }

    /** 这一次补丁纠正了承运商的话用纠正后的（快递100 自动识别会改它） */
    private static String carrierOf(LgsWaybill w, LgsWaybill patch) {
        return patch.getCarrier() != null ? patch.getCarrier() : w.getCarrier();
    }

    private void publish(LgsWaybill w, String carrier, Transition t, TraceResult trace, long signedAt,
                         String channel, long now) {
        if (t.firstPickedUp()) {
            events.publish(new LogisticsEvents.WaybillProgressed(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    carrier, w.getWaybillNo(), WaybillStatus.PICKED_UP, false, now));
        }
        if (WaybillStatus.DELIVERING.equals(t.next()) || WaybillStatus.EXCEPTION.equals(t.next())) {
            events.publish(new LogisticsEvents.WaybillProgressed(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    carrier, w.getWaybillNo(), t.next(), trace != null && trace.atLocker(), now));
        }
        if (t.firstDelivered()) {
            events.publish(new LogisticsEvents.WaybillSigned(w.getShipmentNo(), w.getBizRef(), w.getProfile(),
                    carrier, w.getWaybillNo(), signedAt, channel));
        }
    }

    /** 追加节点，按「时刻 + 原文」去重（快递100 每次推全量）。返回新增条数 */
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
            if (text.length() > 255) {
                text = text.substring(0, 255);
            }
            if (text.isEmpty() || !have.add(n.at() + "|" + text)) {
                continue;
            }
            LgsWaybillNode row = new LgsWaybillNode();
            row.setShipmentNo(shipmentNo);
            row.setAt(n.at());
            row.setText(text);
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

    private static LgsWaybill copyForPolicy(LgsWaybill w, String status) {
        LgsWaybill c = new LgsWaybill();
        c.setProfile(w.getProfile());
        c.setBindState(w.getBindState());
        c.setWxUploadedAt(w.getWxUploadedAt());
        c.setDisplayToken(w.getDisplayToken());
        c.setStatus(status);
        return c;
    }
}
