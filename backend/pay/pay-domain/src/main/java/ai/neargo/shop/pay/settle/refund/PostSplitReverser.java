package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import org.springframework.stereotype.Component;

/** 已发付款意图的退款需要确认资金位置；接受指令不能生成已追回事实。 */
@Component
public class PostSplitReverser {
    private final BillRefundMapper mapper;
    private final BillRefundJournal journal;
    private final FinancialClosurePolicy closurePolicy;

    public PostSplitReverser(BillRefundMapper mapper, BillRefundJournal journal, FinancialClosurePolicy closurePolicy) {
        this.mapper = mapper;
        this.journal = journal;
        this.closurePolicy = closurePolicy;
    }

    public boolean reverse(StlBill bill, BillRefundShare share, String afterSaleNo) {
        if (share.merchantReverseMinor() > 0 || bill.getPayoutNo() != null || bill.getMerchantPaidMinor() == null
                || StlBill.SPLIT.equals(bill.getStatus()) || StlBill.SPLITTING.equals(bill.getStatus())) {
            DataScopeContext.executeWithoutScope(() -> mapper.markManual(bill.getSettleNo(), bill.getStatus(),
                    "REVERSAL_RESULT_UNCONFIRMED: " + afterSaleNo));
            return false;
        }
        long reversed = bill.getReversedMinor() == null ? 0 : bill.getReversedMinor();
        long remaining = bill.getNetMinor() - reversed;
        var closure = closurePolicy.evaluate(remaining, bill.getMerchantPaidMinor(), reversed,
                share.finalRefund() ? remaining : 0, false, share.requiresReview());
        if (closure.needsReview()) {
            DataScopeContext.executeWithoutScope(() -> mapper.markManual(bill.getSettleNo(), bill.getStatus(),
                    "REFUND_FINANCIAL_POSITION_NEEDS_REVIEW: " + afterSaleNo));
            return false;
        }
        journal.append(bill, share, afterSaleNo, false);
        String next = closure.fullyReversed() ? StlBill.REVERSED : bill.getStatus();
        int rows = DataScopeContext.executeWithoutScope(() -> mapper.recordAfterSplit(bill.getSettleNo(),
                share.goodsMinor(), 0, bill.getRefundedGoodsMinor(), bill.getRefundedFreightMinor(),
                share.freightMinor(), bill.getStatus(), next));
        if (rows != 1) throw BizException.of(ErrorCode.CONFLICT);
        return true;
    }
}
