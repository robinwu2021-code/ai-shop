package ai.neargo.shop.pay.settle.refund;

import org.springframework.stereotype.Component;

/** 不以订单退款状态推导结算闭环，不把付款意图视为到账。 */
@Component
public class FinancialClosurePolicy {
    public FinancialClosure evaluate(long remaining, Long paid, long recovered, long retained,
                                     boolean pending, boolean review) {
        boolean unknown = paid == null || remaining < 0 || recovered < 0 || retained < 0
                || (paid != null && (paid < 0 || recovered > paid));
        if (unknown) return new FinancialClosure(remaining, 0, 0, retained, pending, true);
        long netPaid = paid - recovered;
        return new FinancialClosure(remaining, Math.max(remaining - netPaid, 0),
                Math.max(netPaid - remaining, 0), retained, pending, review);
    }
}
