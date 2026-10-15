package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import org.springframework.stereotype.Component;

/**
 * <b>分账前</b>的退款：钱还没分出去，不发任何通道指令，只把结算单各项金额按份额扣掉。
 *
 * <p>之后分账照常按扣减后的金额走 —— 部分退款<b>不再</b>把结算单标成 REVERSED
 * （旧行为：退一件整单不分账，商家余款永远拿不到）。货款全部退完才转 REVERSED。
 */
@Component
public class PreSplitAdjuster {

    private final BillRefundMapper mapper;
    private final BillRefundJournal journal;
    private final FinancialClosurePolicy closurePolicy;

    public PreSplitAdjuster(BillRefundMapper mapper, BillRefundJournal journal, FinancialClosurePolicy closurePolicy) {
        this.closurePolicy = closurePolicy;
        this.mapper = mapper;
        this.journal = journal;
    }

    public boolean adjust(StlBill bill, BillRefundShare share, String afterSaleNo) {
        long remaining = bill.getNetMinor() - share.merchantReverseMinor();
        long retained = share.finalRefund() ? remaining : 0;
        FinancialClosure closure = closurePolicy.evaluate(remaining, 0L, 0, retained, false, share.requiresReview());
        if (closure.needsReview()) {
            DataScopeContext.executeWithoutScope(() -> mapper.markManual(bill.getSettleNo(), bill.getStatus(),
                    "REFUND_FINANCIAL_POSITION_NEEDS_REVIEW: " + afterSaleNo));
            return false;
        }
        journal.append(bill, share, afterSaleNo, true);
        String next = closure.fullyReversed() ? StlBill.REVERSED : bill.getStatus();
        long before = bill.getRefundedGoodsMinor() == null ? 0L : bill.getRefundedGoodsMinor();
        int rows = DataScopeContext.executeWithoutScope(() -> mapper.adjustBeforeSplit(
                bill.getSettleNo(), share.billCuts(), before, bill.getRefundedFreightMinor(),
                share.freightMinor(), bill.getStatus(), next));
        if (rows != 1) {
            // 前值被并发改过（另一笔退款 / 分账任务）：份额是按旧前值算的，不能叠上去
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return true;
    }
}
