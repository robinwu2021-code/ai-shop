package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 锁定同一结算账单后读取双累计位置，金额/流水/CAS作为一个支付域事务。 */
@Component
public class SettlementRefundMutation {
    private final BillMapper bills;
    private final BillRefundMapper refunds;
    private final RefundBasisStore bases;
    private final BillRefundJournal journal;
    private final SettlementRefundPolicy policy;
    private final PreSplitAdjuster preSplit;
    private final PostSplitReverser postSplit;

    public SettlementRefundMutation(BillMapper bills, BillRefundMapper refunds, RefundBasisStore bases,
                                   BillRefundJournal journal, SettlementRefundPolicy policy,
                                   PreSplitAdjuster preSplit, PostSplitReverser postSplit) {
        this.bills = bills; this.refunds = refunds; this.bases = bases; this.journal = journal;
        this.policy = policy; this.preSplit = preSplit; this.postSplit = postSplit;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean apply(SplitReversal cmd) {
        StlBill bill = DataScopeContext.executeWithoutScope(() -> bills.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, cmd.subOrderNo()).last("FOR UPDATE")));
        if (bill == null) return false;
        if (journal.replayMatches(bill, cmd)) return true;
        var basis = bases.find(bill.getSettleNo()).orElse(null);
        if (basis == null || !basis.ready() || bill.getRefundedFreightMinor() == null) return review(bill);
        BillRefundShare share;
        try {
            share = policy.share(basis,
                    new RefundPosition(bill.getRefundedGoodsMinor(), bill.getRefundedFreightMinor()),
                    cmd.goodsMinor(), cmd.freightMinor());
        } catch (IllegalStateException e) { return review(bill); }
        if (share.requiresReview() || !journal.historyReady(bill)) return review(bill);
        if (StlBill.SPLIT.equals(bill.getStatus()) || StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())
                || StlBill.PAID.equals(bill.getStatus()) || StlBill.SPLITTING.equals(bill.getStatus())
                || bill.getPayoutNo() != null
                || (bill.getMerchantPaidMinor() != null && bill.getMerchantPaidMinor() > 0)
                || (StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt() != null)) {
            return postSplit.reverse(bill, share, cmd.afterSaleNo());
        }
        if (StlBill.MANUAL.equals(bill.getStatus())) return review(bill);
        return preSplit.adjust(bill, share, cmd.afterSaleNo());
    }

    private boolean review(StlBill bill) {
        DataScopeContext.executeWithoutScope(() -> refunds.markManual(bill.getSettleNo(), bill.getStatus(),
                "REFUND_BASIS_NEEDS_REVIEW"));
        return false;
    }
}
