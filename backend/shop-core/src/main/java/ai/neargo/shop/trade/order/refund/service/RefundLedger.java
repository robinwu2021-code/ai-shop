package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.refund.model.LedgerResult;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundProgress;
import ai.neargo.shop.trade.order.refund.port.RefundLedgerStore;

/**
 * 退款账本：一笔退款落定时，累加行与子单的已退件数 / 金额，并重算退款进度。
 *
 * <p>只在退款<b>落定</b>（REFUNDED）时记，不在申请时预占 —— 申请可能撤销、驳回，预占要回滚。
 * 必须在调用方事务内调用：任何一步冲突（并发超退）抛 CONFLICT，整笔回滚。
 */
public class RefundLedger {

    private final RefundLedgerStore store;

    public RefundLedger(RefundLedgerStore store) {
        this.store = store;
    }

    public LedgerResult record(RefundPlan plan) {
        for (RefundLine l : plan.lines()) {
            if ((l.qty() > 0 || l.amountMinor() > 0) && !store.addItemRefund(l.itemId(), l.qty(), l.amountMinor())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        if (plan.totalMinor() > 0 && !store.addSubRefund(plan.subOrderNo(), plan.totalMinor())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        long[] rp = store.subRefundedAndPay(plan.subOrderNo());
        RefundProgress progress = RefundProgress.of(rp[0], rp[1]);
        store.setProgress(plan.subOrderNo(), progress.name());
        return new LedgerResult(progress, rp[0]);
    }
}
