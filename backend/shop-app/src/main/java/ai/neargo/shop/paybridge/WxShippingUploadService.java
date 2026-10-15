package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/** The original payment row owns an exact frozen package set and a conditional response revision. */
@Service
public class WxShippingUploadService {
    private static final Logger log = LoggerFactory.getLogger(WxShippingUploadService.class);
    private static final int MAX_ATTEMPTS = 8;
    private final ShippingUploadQueue queue;
    private final ShippingUploadSources sources;
    private final WxShippingPort shipping;
    private final OutboxEventBus events;
    private final ObjectMapper json;

    public WxShippingUploadService(ShippingUploadQueue queue, ShippingUploadSources sources,
                                   WxShippingPort shipping, OutboxEventBus events, ObjectMapper json) {
        this.queue = queue;
        this.sources = sources;
        this.shipping = shipping;
        this.events = events;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void enqueue(String orderNo, String outTradeNo, int logisticsType) {
        if (logisticsType == 0 || blank(outTradeNo)) {
            log.error("[wxship] Cannot queue order {}: missing payment or fulfillment identity", orderNo);
            return;
        }
        queue.lockPayment(orderNo);
        ShippingUploadSnapshot snapshot = sources.snapshotOf(orderNo);
        String frozen = snapshot == null ? null : snapshot.json(json);
        TrdShippingUpload existing = queue.byOrderLocked(orderNo);
        if (existing != null) {
            if (!Objects.equals(existing.getOutTradeNo(), outTradeNo)) { throw BizException.of(ErrorCode.CONFLICT); }
            if (!Objects.equals(ShippingUploadSnapshot.parse(existing.getSourceSnapshot(), json), snapshot)) {
                if (!queue.refresh(existing, frozen)) { throw BizException.of(ErrorCode.CONFLICT); }
            }
            if (!TrdShippingUpload.SUCCESS.equals(existing.getStatus())) {
                events.publish(new WxShippingUploadRequested(orderNo));
            }
            return;
        }
        TrdShippingUpload row = new TrdShippingUpload();
        row.setOrderNo(orderNo);
        row.setOutTradeNo(outTradeNo);
        row.setLogisticsType(logisticsType);
        row.setSourceSnapshot(frozen);
        row.setStatus(TrdShippingUpload.PENDING);
        row.setAttempts(0);
        queue.insert(row);
        events.publish(new WxShippingUploadRequested(orderNo));
    }

    /** Explicit protected Ops retry retains the same payment row and refuses exhausted channel correction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void retry(String orderNo) {
        TrdShippingUpload row = queue.byOrderLocked(orderNo);
        if (row == null || hardCorrectionFailure(row)) { throw BizException.of(ErrorCode.CONFLICT); }
        ShippingUploadSnapshot current = sources.snapshotOf(orderNo);
        String frozen = current == null ? null : current.json(json);
        if (!Objects.equals(current, ShippingUploadSnapshot.parse(row.getSourceSnapshot(), json))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (!TrdShippingUpload.SUCCESS.equals(row.getStatus()) && !queue.refresh(row, frozen)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean upload(TrdShippingUpload row, String itemDesc,
                          String trackingNo, String expressCompany, String payerOpenid) {
        if (!TrdShippingUpload.PENDING.equals(row.getStatus())) { return true; }
        ShippingUploadSnapshot frozen = ShippingUploadSnapshot.parse(row.getSourceSnapshot(), json);
        if (!current(row, frozen)) { return false; }
        if (frozen != null && frozen.blocker() != null) { queue.blocked(row, frozen.blocker()); return false; }
        WxShippingPort.Command command = frozen == null
                ? new WxShippingPort.Command(row.getOutTradeNo(), row.getLogisticsType(), itemDesc,
                        trackingNo, expressCompany, payerOpenid)
                : new WxShippingPort.Command(row.getOutTradeNo(), row.getLogisticsType(), itemDesc,
                        trackingNo, expressCompany, payerOpenid, frozen.shipments(), frozen.deliveryMode(),
                        frozen.allDelivered(), frozen.shipments().stream().anyMatch(s -> s.sourceShipmentNo() != null));
        WxShippingPort.Result response = shipping.upload(command);
        Outcome outcome = outcomeOf(row.getAttempts() == null ? 0 : row.getAttempts(), response);
        if (!queue.finish(row, outcome, response.success() ? null : response.code(), response.message())) {
            return false;
        }
        if (response.success()) {
            events.publish(new OrderEvents.WxShippingUploaded(row.getOrderNo(), row.getOutTradeNo(),
                    System.currentTimeMillis(), frozen == null ? List.of() : frozen.shipments()));
        }
        return outcome.giveUp();
    }

    private boolean current(TrdShippingUpload row, ShippingUploadSnapshot frozen) {
        if (frozen == null) { return true; }
        ShippingUploadSnapshot current = sources.snapshotOf(row.getOrderNo());
        if (Objects.equals(frozen, current)) { return true; }
        queue.blocked(row, "发货来源已改变，等待原始发货事件刷新同一支付队列");
        return false;
    }

    @Transactional
    public void blocked(TrdShippingUpload row, String reason) { queue.blocked(row, reason); }

    public static Outcome outcomeOf(int attemptsBefore, WxShippingPort.Result response) {
        int attempts = attemptsBefore + 1;
        if (response.success()) { return new Outcome(attempts, true, TrdShippingUpload.SUCCESS); }
        boolean giveUp = !response.retryable() || attempts >= MAX_ATTEMPTS;
        return new Outcome(attempts, giveUp, giveUp ? TrdShippingUpload.FAILED : TrdShippingUpload.PENDING);
    }

    public record Outcome(int attempts, boolean giveUp, String status) { }

    public List<TrdShippingUpload> pending(int limit) { return queue.pending(limit); }
    public String statusOf(Long id) { return queue.statusOf(id); }

    static boolean hardCorrectionFailure(TrdShippingUpload row) {
        return row.getErrCode() != null && (row.getErrCode() == 10060002 || row.getErrCode() == 10060003);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
