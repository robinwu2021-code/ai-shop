package ai.neargo.shop.pay.settle.refund;

/** 应付与实际资金位置相互独立；未知值不能作为零金额结清。 */
public record FinancialClosure(long remainingPayableMinor, long unpaidPayableMinor,
                               long outstandingRecoveryMinor, long legitimateRetainedMinor,
                               boolean pending, boolean needsReview) {
    public boolean fullyReversed() {
        return remainingPayableMinor == 0 && unpaidPayableMinor == 0 && outstandingRecoveryMinor == 0
                && !pending && !needsReview;
    }

    public boolean settlementReady() {
        return unpaidPayableMinor > 0 && !pending && !needsReview;
    }
}
