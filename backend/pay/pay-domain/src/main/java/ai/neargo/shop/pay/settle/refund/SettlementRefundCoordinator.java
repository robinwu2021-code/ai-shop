package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.spi.settle.SettlePort.ReversalResult;
import ai.neargo.shop.spi.settle.SettlePort.ReversalStage;
import ai.neargo.shop.spi.settle.SettlePort.ReversalOrderFact;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** 历史Basis补录与资金修改分开事务；修改事务只读取已提交的不可变事实。 */
@Component
public class SettlementRefundCoordinator {
    private final BillMapper bills;
    private final RefundBasisStore bases;
    private final SettlementRefundMutation mutation;
    private final NoBillReversalGuard noBill;

    public SettlementRefundCoordinator(BillMapper bills, RefundBasisStore bases, SettlementRefundMutation mutation,
                                       NoBillReversalGuard noBill) {
        this.noBill = noBill;
        this.bills = bills;
        this.bases = bases;
        this.mutation = mutation;
    }

    public ReversalResult reverseStage(SplitReversal cmd, ReversalOrderFact orderFact) {
        StlBill bill = DataScopeContext.executeWithoutScope(() -> bills.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, cmd.subOrderNo()).last("LIMIT 1")));
        if (bill == null) return noBill.check(cmd.subOrderNo(), orderFact);
        if (bases.find(bill.getSettleNo()).isEmpty()) bases.backfill(bill, cmd.goodsPaidMinor());
        return mutation.apply(cmd) ? new ReversalResult(ReversalStage.ADJUSTED, null)
                : new ReversalResult(ReversalStage.NEEDS_REVIEW, "BASIS_OR_FUNDING_RESULT_UNCONFIRMED");
    }
}
