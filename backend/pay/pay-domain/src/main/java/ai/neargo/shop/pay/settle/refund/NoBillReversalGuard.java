package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBillRefundBasis;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.RefundBasisMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper;
import ai.neargo.shop.spi.settle.SettlePort.ReversalResult;
import ai.neargo.shop.spi.settle.SettlePort.ReversalStage;
import ai.neargo.shop.spi.settle.SettlePort.ReversalOrderFact;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/** 缺账单是一个需要证明的阶段；有资金意图或应有账单时不能继续现金退款。 */
@Component
public class NoBillReversalGuard {
    private final PaymentMapper payments;
    private final SplitLogMapper splits;
    private final RefundBasisMapper bases;
    private final PaymentSettlementSerialPoint serial;

    public NoBillReversalGuard(PaymentMapper payments, SplitLogMapper splits, RefundBasisMapper bases,
                              PaymentSettlementSerialPoint serial) {
        this.payments = payments; this.splits = splits; this.bases = bases; this.serial = serial;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ReversalResult check(String subOrderNo, ReversalOrderFact orderFact) {
        if (orderFact == null) return review("ORDER_CONTEXT_UNKNOWN");
        serial.lock(orderFact.payOrderNo());
        boolean intent = DataScopeContext.executeWithoutScope(() ->
                !splits.selectList(Wrappers.<StlSplitLog>lambdaQuery()
                        .eq(StlSplitLog::getSubOrderNo, subOrderNo).last("FOR UPDATE")).isEmpty()
                || bases.selectCount(Wrappers.<StlBillRefundBasis>lambdaQuery()
                        .eq(StlBillRefundBasis::getSubOrderNo, subOrderNo)) > 0
                || !payments.selectList(Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getOrderNo, orderFact.payOrderNo())
                        .in(StlPayment::getDirection, StlPayment.PAYOUT, StlPayment.SUBSIDY)
                        .notIn(StlPayment::getStatus, StlPayment.FAILED, StlPayment.CLOSED)
                        .last("FOR UPDATE")).isEmpty());
        if (intent || orderFact.billExpected() || !orderFact.unpaidVerified()) {
            return review("EXPECTED_BILL_OR_PAYMENT_INTENT");
        }
        return new ReversalResult(ReversalStage.NOT_REQUIRED, "NO_BILL_AND_NO_PAYMENT_INTENT");
    }

    private static ReversalResult review(String reason) {
        return new ReversalResult(ReversalStage.NEEDS_REVIEW, reason);
    }
}
