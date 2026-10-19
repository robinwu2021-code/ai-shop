package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxLease;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsKd100SubAttempt;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/** Dedicated Kuaidi100 subscription; its result never gates or overwrites WeChat follow. */
@Component
public class Kd100SubscribeExecutor implements OutboxConsumer {
    private static final String CHANNEL = "kuaidi100";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private final WaybillMapper waybills;
    private final ShipmentSourcePort source;
    private final ChannelSubscriptionStore subscriptions;
    private final Kd100AttemptStore attempts;
    private final ChannelRouter router;
    private final SubscribeExecutor snapshots;
    private final LogisticsProperties props;
    private final ObjectMapper json;

    public Kd100SubscribeExecutor(WaybillMapper waybills, ShipmentSourcePort source,
            ChannelSubscriptionStore subscriptions, Kd100AttemptStore attempts, ChannelRouter router,
            SubscribeExecutor snapshots, LogisticsProperties props, ObjectMapper json) {
        this.waybills = waybills;
        this.source = source;
        this.subscriptions = subscriptions;
        this.attempts = attempts;
        this.router = router;
        this.snapshots = snapshots;
        this.props = props;
        this.json = json;
    }

    @Override
    public DeliveryResult deliver(SysOutbox event) {
        if (!props.isSubscribeEnabled()) { return DeliveryResult.failed("DISABLED", "Logistics subscriptions disabled"); }
        consume(event);
        var p = json.readTree(event.getPayload());
        var row = subscriptions.find(p.path("shipmentNo").asString(""), p.path("identityKey").asString(""), CHANNEL);
        if (row != null && java.util.Set.of("CONFLICT", "FATAL", "UNAVAILABLE").contains(row.getState())) {
            return DeliveryResult.failed("SUBSCRIBE_FAILED", "See kuaidi100 action state");
        }
        if (row != null && java.util.Set.of("PENDING", "ATTEMPTING", "RETRYABLE", "WAITING").contains(row.getState())) {
            return DeliveryResult.retry("SUBSCRIBE_NOT_READY", "See kuaidi100 action state");
        }
        return DeliveryResult.sent();
    }

    @Override public String handlerId() { return "logistics-kd100-subscribe"; }
    @Override public boolean externalWorkflow() { return true; }

    @Override public boolean supports(String type) { return Kd100SubscribeRequested.TYPE.equals(type); }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var payload = json.readTree(event.getPayload());
        String shipmentNo = payload.path("shipmentNo").asString("");
        String expectedIdentity = payload.path("identityKey").asString("");
        LgsWaybill waybill = waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("shipment_no", shipmentNo).last("LIMIT 1"));
        if (waybill == null || !expectedIdentity.equals(ChannelSubscriptionStore.identityKey(waybill))
                || !WaybillSourceIdentity.matches(waybill,
                    source.identityOf(waybill.getBizRef()).orElse(null))) return;
        LgsChannelSubscription row = subscriptions.ensurePending(waybill, CHANNEL);
        String phone = snapshots.snapshot(waybill);
        if ("DONE".equals(row.getState()) || "CONFLICT".equals(row.getState())
                || "FATAL".equals(row.getState())) return;
        if (WaybillStatus.isTerminal(waybill.getStatus())) {
            subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "NA", null, "物流已到终态");
            return;
        }
        if (!props.isSubscribeEnabled()) return;
        Optional<String> blocker = router.subscribeBlocker(CHANNEL, waybill.getCarrier());
        if (blocker.isPresent()) {
            subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "UNAVAILABLE", null, blocker.get());
            return;
        }
        TrackingSubscriber subscriber = router.subscriber(CHANNEL).orElseThrow();
        String companyCode = router.codeOf(waybill.getCarrier(), subscriber).orElse(null);
        if (companyCode == null) {
            subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "FATAL", null, "快递公司没有快递100编码");
            return;
        }
        if (!subscriptions.claim(shipmentNo, expectedIdentity, CHANNEL)) return;
        String callbackId = UUID.randomUUID().toString().replace("-", "");
        Optional<LgsKd100SubAttempt> reserved = attempts.reserveAttempt(props.getKd100AccountRef(), companyCode,
                waybill.getWaybillNo(), YearMonth.now().format(MONTH), shipmentNo, expectedIdentity);
        if (reserved.isEmpty()) {
            subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "FATAL", null, "本月订阅尝试已达4次");
            return;
        }
        LgsKd100SubAttempt attempt = reserved.orElseThrow();
        attempts.markResult(attempt.getId(), "RESERVED", null, callbackId);
        OutboxLease.check();
        ChannelOutcome result = subscriber.subscribe(new TrackingSubscriber.SubscribeCmd(waybill.getCarrier(),
                companyCode, waybill.getWaybillNo(), phone,
                props.getCallbackBase() + "/" + CHANNEL + "?callbackId=" + callbackId));
        attempts.markResult(attempt.getId(), result.kind().name(), result.code(), callbackId);
        if (result.kind() == ChannelOutcome.Kind.OK && "501".equals(result.code())) {
            subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "CONFLICT", null,
                    "501 表示已有活动订阅，无法证明其回调属于当前发货");
            return;
        }
        switch (result.kind()) {
            case OK -> {
                subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "DONE", result.ref(), null);
                LgsWaybill patch = LgsWaybill.patch(waybill.getId());
                patch.setSubState(LgsWaybill.SUB_DONE);
                patch.setSubChannel(CHANNEL);
                patch.setSubRef(result.ref());
                waybills.update(patch, WaybillSourceIdentity.unchanged(waybill));
            }
            case RETRYABLE, NOT_READY -> {
                subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "RETRYABLE", null,
                        result.code() + " " + result.message());
                if ("IO".equals(result.code())) { throw new IllegalStateException("Kuaidi100 subscription outcome unknown"); }
                throw new RetryableOutboxException("Kuaidi100 subscription rejected; retryable");
            }
            case FATAL -> subscriptions.mark(shipmentNo, expectedIdentity, CHANNEL, "FATAL", null,
                    result.code() + " " + result.message());
        }
    }
}
