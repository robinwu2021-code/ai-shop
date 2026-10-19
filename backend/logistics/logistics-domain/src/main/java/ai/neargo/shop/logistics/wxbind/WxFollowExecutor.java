package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxLease;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillFollower;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;
import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.registration.ChannelSubscriptionStore;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort.ShipmentSource;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Follows an actual purchase only after local shipment commit; each attempt is independent of Kuaidi100. */
@Component
public class WxFollowExecutor implements OutboxConsumer {
    private final WaybillMapper waybills;
    private final ShipmentSourcePort source;
    private final ChannelSubscriptionStore subscriptions;
    private final CarrierCodeBook codes;
    private final WaybillFollower gateway;
    private final WxFollowResultWriter writer;
    private final ObjectMapper json;

    public WxFollowExecutor(WaybillMapper waybills, ShipmentSourcePort source,
            ChannelSubscriptionStore subscriptions, CarrierCodeBook codes, WaybillFollower gateway,
            WxFollowResultWriter writer, ObjectMapper json) {
        this.waybills = waybills;
        this.source = source;
        this.subscriptions = subscriptions;
        this.codes = codes;
        this.gateway = gateway;
        this.writer = writer;
        this.json = json;
    }

    @Override
    public DeliveryResult deliver(SysOutbox event) {
        var p = json.readTree(event.getPayload());
        String shipmentNo = p.path("shipmentNo").asString("");
        var w = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("LIMIT 1"));
        if (w == null || !p.path("identityKey").asString("").equals(ChannelSubscriptionStore.identityKey(w))) {
            return DeliveryResult.sent();
        }
        var result = followNow(shipmentNo);
        return switch (result.kind()) {
            case OK -> DeliveryResult.sent();
            case RETRYABLE -> "IO".equals(result.code())
                    ? DeliveryResult.unknown("See wx-follow action state")
                    : DeliveryResult.retry("FOLLOW_RETRYABLE", "See wx-follow action state");
            case NOT_READY -> DeliveryResult.retry("FOLLOW_NOT_READY", "See wx-follow action state");
            case FATAL -> DeliveryResult.failed("FOLLOW_FAILED", "See wx-follow action state");
        };
    }

    @Override public String handlerId() { return "logistics-wx-follow"; }
    @Override public boolean externalWorkflow() { return true; }

    @Override public boolean supports(String type) { return WxFollowRequested.TYPE.equals(type); }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var payload = json.readTree(event.getPayload());
        String shipmentNo = payload.path("shipmentNo").asString("");
        String expectedIdentity = payload.path("identityKey").asString("");
        LgsWaybill current = waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("shipment_no", shipmentNo).last("LIMIT 1"));
        if (current == null || !expectedIdentity.equals(ChannelSubscriptionStore.identityKey(current))) return;
        ChannelOutcome outcome = followNow(shipmentNo);
        if (outcome.kind() == ChannelOutcome.Kind.RETRYABLE) {
            throw new IllegalStateException("微信关注待重试：" + outcome.code());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChannelOutcome followNow(String shipmentNo) {
        LgsWaybill waybill = waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("shipment_no", shipmentNo).last("LIMIT 1"));
        if (waybill == null) return ChannelOutcome.retryable("NOT_REGISTERED", "运单尚未登记");
        Optional<ShipmentSource> found = source.sourceOf(waybill.getBizRef());
        if (found.isEmpty()) return ChannelOutcome.retryable("NO_SOURCE", "发货快照尚未就绪");
        ShipmentSource shipment = found.orElseThrow();
        if (!WaybillSourceIdentity.matches(waybill, new ShipmentSourcePort.ShipmentIdentity(
                shipment.sourceShipmentNo(), shipment.sourceShipmentVersion(), shipment.carrier(), shipment.waybillNo()))) {
            return ChannelOutcome.fatal("STALE_SHIPMENT", "当前发货身份已变化");
        }
        String identity = ChannelSubscriptionStore.identityKey(waybill);
        LgsChannelSubscription row = subscriptions.ensurePending(waybill, "wx-follow");
        if ("DONE".equals(row.getState()) && row.getRef() != null) {
            writer.recordSuccess(waybill, row.getRef(), gateway.queryFollow(row.getRef(), waybill.getCarrier(), waybill.getWaybillNo()));
            return ChannelOutcome.ok("ALREADY_FOLLOWED", row.getRef());
        }
        if (shipment.wx() == null || blank(shipment.wx().openid()) || blank(shipment.wx().transId())) {
            subscriptions.mark(shipmentNo, identity, "wx-follow", "NOT_ELIGIBLE", null, "缺少微信支付交易信息");
            return ChannelOutcome.fatal("NOT_ELIGIBLE", "缺少微信支付交易信息");
        }
        if (!gateway.available()) {
            subscriptions.mark(shipmentNo, identity, "wx-follow", "NOT_ELIGIBLE", null, "微信物流消息能力未配置");
            return ChannelOutcome.fatal("NOT_ELIGIBLE", "微信物流消息能力未配置");
        }
        if (!subscriptions.claim(shipmentNo, identity, "wx-follow")) {
            return ChannelOutcome.notReady("IN_PROGRESS", "微信关注任务正在执行或已结束");
        }
        String goods = json.writeValueAsString(shipment.goods() == null ? List.of() : shipment.goods().stream()
                .limit(3).map(g -> Map.of("name", g.name(), "imageUrl", g.imageUrl() == null ? "" : g.imageUrl())).toList());
        OutboxLease.check();
        ChannelOutcome outcome = gateway.follow(new WaybillFollower.FollowCmd(
                shipment.wx().openid(), shipment.wx().transId(), shipment.waybillNo(),
                codes.codeOf(shipment.carrier(), "wx").orElse(null), shipment.receiverPhone(),
                goods, shipment.orderPath()));
        switch (outcome.kind()) {
            case OK -> writer.recordSuccess(waybill, outcome.ref(),
                    gateway.queryFollow(outcome.ref(), waybill.getCarrier(), waybill.getWaybillNo()));
            case NOT_READY -> subscriptions.mark(shipmentNo, identity, "wx-follow", "WAITING", null,
                    outcome.code() + " " + outcome.message());
            case RETRYABLE -> subscriptions.mark(shipmentNo, identity, "wx-follow", "RETRYABLE", null,
                    outcome.code() + " " + outcome.message());
            case FATAL -> subscriptions.mark(shipmentNo, identity, "wx-follow", "FATAL", null,
                    outcome.code() + " " + outcome.message());
        }
        return outcome;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
