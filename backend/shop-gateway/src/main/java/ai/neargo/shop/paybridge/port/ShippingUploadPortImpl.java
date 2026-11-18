package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.common.WxLogisticsTypes;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.paybridge.ShippingUploadSources;
import java.util.Objects;
import ai.neargo.shop.spi.trade.ShippingUploadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ShippingUploadPort} 的实现：把交易域的「履约方式」翻成微信的
 * {@code logistics_type}，配上支付单号，落进上报台账。
 *
 * <h2>映射只在这一处发生</h2>
 * 交易域传过来的是 {@code EXPRESS} / {@code STORE_PICKUP} 这类我们自己的词，
 * 翻成 {@code 1/2/3/4} 是这一行的事。见 {@link WxLogisticsTypes} 的类注释 ——
 * 散成两处的表现不是报错，是报上去但语义错。
 *
 * <h2>只落库，且绝不把异常抛回去</h2>
 * 调用点是商家点「发货」、自提点点「到货」、支付回调推成功 ——
 * 这三个动作都不该因为「微信上报的台账没写进去」而失败。
 * 写不进去是要人来看的事（钱会结不出来），但让商家点不动发货
 * 并不能让它更快被看见，只会多一个故障。
 */
@Component
public class ShippingUploadPortImpl implements ShippingUploadPort, OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShippingUploadPortImpl.class);

    private final WxShippingUploadService uploads;
    private final PaymentLedgerService ledger;
    private final ObjectMapper json = new ObjectMapper();
    private final ShippingUploadSources sources;

    public ShippingUploadPortImpl(WxShippingUploadService uploads, PaymentLedgerService ledger,
                                  ShippingUploadSources sources) {
        this.uploads = uploads;
        this.ledger = ledger;
        this.sources = sources;
    }

    @Override public String handlerId() { return "wx-shipping-queue"; }
    // The upload queue commits in its own REQUIRES_NEW action transaction.
    @Override public boolean externalWorkflow() { return true; }

    @Override
    public void enqueue(String orderNo, String subOrderNo, String fulfillment) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { enqueueSafely(orderNo, subOrderNo, fulfillment); }
            });
            return;
        }
        enqueueSafely(orderNo, subOrderNo, fulfillment);
    }

    private void enqueueSafely(String orderNo, String subOrderNo, String fulfillment) {
        try {
            enqueueCommitted(orderNo, subOrderNo, fulfillment);
        } catch (RuntimeException e) {
            log.error("[wxship] 订单 {} 入队失败；原 SUB_ORDER_SHIPPED 事件可重放恢复", orderNo, e);
        }
    }

    @Override
    public boolean supports(String type) {
        return "SUB_ORDER_SHIPPED".equals(type) || "AFTER_SALE_REFUNDED".equals(type);
    }

    @Override
    public void consume(SysOutbox event) {
        var p = json.readTree(event.getPayload());
        if ("AFTER_SALE_REFUNDED".equals(event.getEventType())) {
            refreshAfterRefund(p.path("subOrderNo").asString(""));
            return;
        }
        String orderNo = p.path("orderNo").asString("");
        String subOrderNo = p.path("subOrderNo").asString("");
        var current = sources.snapshotOf(orderNo);
        String source = p.path("sourceShipmentNo").asString(null);
        if (source != null && (current == null || !current.contains(subOrderNo, source,
                p.path("sourceShipmentVersion").asLong(-1), p.path("expressCompany").asString(null),
                p.path("expressNo").asString(null)))) { return; }
        if (source == null && current != null && current.shipments().stream()
                .anyMatch(s -> Objects.equals(subOrderNo, s.subOrderNo()) && s.sourceShipmentNo() != null)) { return; }
        enqueueCommitted(p.path("orderNo").asString(""), p.path("subOrderNo").asString(""),
                p.path("fulfillment").asString(""));
    }

    private void refreshAfterRefund(String subOrderNo) {
        String paymentNo = sources.paymentOfRefundedChild(subOrderNo);
        if (paymentNo == null) { return; }
        var snapshot = sources.snapshotOf(paymentNo);
        if (snapshot == null || snapshot.shipments().isEmpty()) { return; }
        // Reuse the original row and its CAS/correction safeguards; never fabricate a new package.
        enqueueCommitted(paymentNo, subOrderNo, "EXPRESS");
    }

    private void enqueueCommitted(String orderNo, String subOrderNo, String fulfillment) {
        int type = WxLogisticsTypes.of(fulfillment);
        if (type == 0) {
            /*
             * 认不出来不兜默认值 —— 兜 1（快递）会让这类单缺运单号被微信拒（至少看得见），
             * 兜 3（虚拟）是报上去但语义错，微信不拒。`enqueue` 里也有同样一道，
             * 这里先拦是为了把「哪张子单、什么履约方式」带进日志。
             */
            log.error("[wxship] 子单 {}（订单 {}）的履约方式 {} 认不出来，**不上报**"
                    + " —— 这笔钱会结不出来，要人来看", subOrderNo, orderNo, fulfillment);
            return;
        }
        var paid = ledger.paidPayment(orderNo);
        if (paid.isEmpty()) {
            /*
             * **不是缺陷，是时序**：线下付款（confirm-offline-pay）这类单
             * 压根没有微信支付流水，自然也不需要向微信报发货。
             * 但「该有却没有」也长这个样子，所以记 WARN 留个线索，不记 ERROR。
             */
            log.warn("[wxship] 订单 {} 没有付成功的微信收款流水，跳过上报（线下付款的单正常会走到这里）",
                    orderNo);
            return;
        }
        uploads.enqueue(orderNo, paid.get().outTradeNo(), type);
    }
}
