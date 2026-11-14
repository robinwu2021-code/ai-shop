package ai.neargo.shop.pay.funds;

import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.ledger.LedgerVoucher;

/**
 * 资金路径的分录规则。<b>DIRECT 与 AGGREGATED 的差异只在这里</b>（代码清单 §2.3）：别处一律只问「记一张凭证」，
 * 不问钱进了谁的账户。
 */
public interface FundsPathStrategy {

    /** {@code DIRECT} / {@code AGGREGATED} */
    String fundsPath();

    /** 支付单成功：一张 {@code PAY_SUCCEEDED} */
    LedgerVoucher onPaymentSucceeded(PayOrder order, long amountMinor, String payChannel);

    /**
     * 退款成功：一张 {@code REFUND_SUCCEEDED}
     *
     * @param refundId 退款单 / 退款尝试 id（幂等键）
     */
    LedgerVoucher onRefundSucceeded(PayOrder order, Long refundId, long amountMinor, String payChannel);
}
