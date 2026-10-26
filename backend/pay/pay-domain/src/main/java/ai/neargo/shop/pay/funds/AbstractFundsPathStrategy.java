package ai.neargo.shop.pay.funds;

import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.ledger.LedgerAccount;
import ai.neargo.shop.pay.ledger.LedgerVoucher;
import ai.neargo.shop.pay.ledger.LedgerVoucher.Direction;
import ai.neargo.shop.pay.ledger.LedgerVoucher.Line;
import java.time.Instant;
import java.util.List;

/** 两条路径共用的凭证骨架；子类只给出「钱从哪个账户到哪个账户」 */
abstract class AbstractFundsPathStrategy implements FundsPathStrategy {

    /** 收款：借记账户（钱在哪） */
    protected abstract LedgerAccount collectedInto();

    /** 收款：贷记账户（对谁的义务 / 收入） */
    protected abstract LedgerAccount owedTo();

    @Override
    public LedgerVoucher onPaymentSucceeded(PayOrder o, long amount, String channel) {
        return voucher("PAY_SUCCEEDED", "PAY_ORDER", o.getId(), "PAY_SUCCEEDED|PAY_ORDER|" + o.getId(), o, amount, channel,
                collectedInto(), owedTo(), "支付成功 " + o.getPayOrderNo());
    }

    @Override
    public LedgerVoucher onRefundSucceeded(PayOrder o, Long refundId, long amount, String channel) {
        return voucher("REFUND_SUCCEEDED", "PAY_REFUND", refundId, "REFUND_SUCCEEDED|PAY_REFUND|" + refundId, o, amount,
                channel, owedTo(), collectedInto(), "退款成功 " + o.getPayOrderNo());
    }

    private LedgerVoucher voucher(String type, String refType, Long refId, String idem, PayOrder o, long amount,
                                  String channel, LedgerAccount debit, LedgerAccount credit, String memo) {
        return new LedgerVoucher(type, refType, refId, idem, o.getMarket(), o.getCurrency(),
                BizDates.of(o.getMarket(), Instant.now()), fundsPath(), o.getSourceType(), o.getSourceId(), memo,
                List.of(new Line(debit, Direction.DR, amount, o.getStoreId(), o.getPayMerchantId(), channel, o.getId()),
                        new Line(credit, Direction.CR, amount, o.getStoreId(), o.getPayMerchantId(), channel, o.getId())));
    }
}
