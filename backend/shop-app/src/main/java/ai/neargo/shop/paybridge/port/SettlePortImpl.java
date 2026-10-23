package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.paybridge.SettleGenerationOrchestrator;
import ai.neargo.shop.paybridge.service.PaymentDispatchService;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link SettlePort} 实现 —— <b>薄适配器，逻辑全在 {@link SettleService}</b>。
 *
 * <p>这个类只转发三个调用，看起来像多余的一层。留着它是因为两个接口的<b>受众不同</b>：
 *
 * <ul>
 *   <li>{@code SettleService} 还有 {@code merchantBills} / {@code rateCard} 这些
 *       给 B 端商家看账单的读方法 —— 那是本域自己的事</li>
 *   <li>{@code SettlePort} 只暴露 trade 域真正需要的三个写操作：生成、回退、退款</li>
 * </ul>
 *
 * <p>合成一个类的话，trade 会拿到整个 SettleService，包括商家账单查询 ——
 * 一个域能看到另一个域的全部能力，边界就只剩注释在维护了。
 *
 * <p>另一个实际好处：将来结算独立部署时，改的是这个适配器（换成 RPC 客户端），
 * {@code SettleServiceImpl} 与所有调用方都不动。
 *
 * <h2>2026-09-01：从 pay-domain 搬到 shop-app/paybridge</h2>
 * 因为 {@code generateForOrder} 不再是转发，而是<b>编排</b> ——
 * 它要把「子单构成」（trade）与「商家属性快照」（merchant）组装好再交给支付域。
 * 那两样支付域都不该自己去查（反向依赖），而这一层同时够得着两边。
 *
 * <p>其余四个方法仍是纯转发。<b>放在一起是因为它们实现同一个接口</b> ——
 * 拆成两个 bean 的话，调用方要知道「哪个方法找哪个实现」，
 * 而那正是这个 Port 存在要挡掉的事。
 */
@Component
public class SettlePortImpl implements SettlePort {

    private static final Logger log = LoggerFactory.getLogger(SettlePortImpl.class);

    private final SettleService settleService;
    private final PaymentLedgerService paymentLedger;

    private final SettleGenerationOrchestrator orchestrator;
    /** 退款时的结算侧编排（按额回退分账，口径 A） */
    private final ai.neargo.shop.pay.settle.refund.SettlementRefundCoordinator settlementRefund;
    private final ai.neargo.shop.pay.settle.refund.CashRefundSplitService cashRefundSplit;

    public SettlePortImpl(SettleService settleService, PaymentLedgerService paymentLedger,
                          SettleGenerationOrchestrator orchestrator,
                          ai.neargo.shop.pay.settle.refund.SettlementRefundCoordinator settlementRefund,
                          ai.neargo.shop.pay.settle.refund.CashRefundSplitService cashRefundSplit) {
        this.settlementRefund = settlementRefund;
        this.cashRefundSplit = cashRefundSplit;
        this.settleService = settleService;
        this.paymentLedger = paymentLedger;
        this.orchestrator = orchestrator;
    }

    private RefundExecutionService refunds;
    private ObjectProvider<BasicRefundExecutionService> refundExecutions;

    @Autowired
    public void setRefundExecutionService(RefundExecutionService refunds) {
        this.refunds = refunds;
    }

    @Autowired
    public void setRefundExecutions(ObjectProvider<BasicRefundExecutionService> refundExecutions) {
        this.refundExecutions = refundExecutions;
    }

    @Override
    public RefundDispatchResult settleRefund(String no, String channel, long amount, String currency, String provider) {
        RefundDispatchResult confirmed = refunds.confirm(no, channel, amount, currency, provider);
        if (confirmed.status() == DispatchStatus.SUCCEEDED && confirmed.executionNo() != null) {
            try {
                refundExecutions.getObject().applyResult(confirmed);
            } catch (RuntimeException projectionFailure) {
                log.warn("refund {} cash confirmed; original execution awaits projection recovery",
                        confirmed.executionNo(), projectionFailure);
            }
        }
        return confirmed;
    }

    @Override
    public PaymentReceipt refundOrigin(String orderNo, String tradeNo, String payChannel) {
        return refunds.origin(orderNo, tradeNo, payChannel);
    }

    @Override
    public boolean prepareRefundReversal(SplitReversal request) {
        return refunds.reverseUnsplit(request);
    }

    @Override
    public boolean prepareRefundReversal(SplitReversal request, boolean zeroLiability) {
        return refunds.reverseUnsplit(request, zeroLiability);
    }

    @Override
    public RefundDispatchResult requestRefund(RefundRequest request) {
        return refunds.request(request);
    }

    @Override
    public RefundDispatchResult queryRefundExecution(String executionNo) {
        return refunds.query(executionNo);
    }

    @Override
    public RefundDispatchResult queryRefundExecution(RefundRequest request) {
        return refunds.query(request);
    }

    @Override
    public int generateForOrder(String orderNo) {
        // 编排在 SettleGenerationOrchestrator：组装子单构成 + 商家属性快照，
        // 那两样支付域都不该自己去查
        return orchestrator.generateForOrder(orderNo);
    }

    @Override
    public String openPayment(PaymentOpen cmd) {
        return paymentLedger.open(cmd);
    }

    @Autowired private PaymentDispatchService paymentDispatch;

    @Override
    public PayInitResult initPayment(PaymentOpen cmd) {
        return paymentDispatch.initiateLegacy(cmd);
    }

    @Override
    public PaymentReceipt successPayment(String orderNo) {
        return paymentLedger.successPayment(orderNo);
    }

    @Override
    public PaymentReceipt successPayment(String orderNo, String channel, String tradeNo) {
        return paymentLedger.successPayment(orderNo, channel, tradeNo);
    }

    @Override
    public PaymentReceipt paymentByOutTradeNo(String outTradeNo) {
        return paymentLedger.paymentByOutTradeNo(outTradeNo);
    }

    @Override
    public String settlePayment(PaymentSettled cmd) {
        return paymentLedger.settle(cmd);
    }


    @Override
    public boolean reverseSplit(String subOrderNo) {
        return settleService.reverseSplit(subOrderNo);
    }

    @Override
    public boolean reverseSplit(SplitReversal cmd) {
        return settlementRefund.reverse(cmd);
    }

    @Override
    public boolean reverseCashRefund(SplitReversal cmd) {
        return cashRefundSplit.reverse(cmd);
    }

    @Override
    public boolean reversePreparedRefund(SplitReversal cmd) {
        return settlementRefund.reversePrepared(cmd) && cashRefundSplit.reversePrepared(cmd);
    }

    @Override
    public boolean confirmPreparedRefund(SplitReversal cmd, String preparationNo, String inputHash) {
        return settlementRefund.confirmPrepared(cmd, preparationNo, inputHash);
    }

    @Override
    public boolean cancelPreparedRefund(SplitReversal cmd, String preparationNo, String inputHash) {
        return settlementRefund.cancelPrepared(cmd, preparationNo, inputHash);
    }

    @Override
    public void refreshRefund(String paymentNo) {
        settleService.refreshRefund(paymentNo);
    }

    @Override
    public String refund(String subOrderNo, long amountMinor, String reason) {
        return settleService.refund(subOrderNo, amountMinor, reason);
    }

    @Override
    public String refund(String subOrderNo, String afterSaleNo, long amountMinor, String reason) {
        return settleService.refund(subOrderNo, afterSaleNo, amountMinor, reason);
    }

    @Override
    public String prepareRefund(String subOrderNo, String afterSaleNo, long amountMinor, String reason) {
        return settleService.prepareRefund(subOrderNo, afterSaleNo, amountMinor, reason);
    }

    @Override
    public void dispatchRefund(String paymentNo) {
        settleService.dispatchRefund(paymentNo);
    }

    @Override
    public String settleRefund(String outRefundNo,String providerNo,long verifiedMinor) {
        return paymentLedger.settleRefundByOutTradeNo(outRefundNo,providerNo,verifiedMinor);
    }
    @Override public RefundEvidence refundEvidence(String no) { return paymentLedger.refundEvidence(no); }
    @Override public boolean hasRefundForAfterSale(String no) { return paymentLedger.hasRefundForAfterSale(no); }
    @Override public String paymentCurrency(String orderNo) { return paymentLedger.paymentCurrency(orderNo); }
}
