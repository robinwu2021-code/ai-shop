package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlBillRefund;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** 旧账只有不存在退款/回退意图时才可信补录，未知资金结果也保留核对。 */
@Component
public class RefundBasisHistory {
    private final BillRefundMapper refunds;
    private final PaymentMapper payments;
    private final SplitLogMapper splits;

    public RefundBasisHistory(BillRefundMapper refunds, PaymentMapper payments, SplitLogMapper splits) {
        this.refunds = refunds;
        this.payments = payments;
        this.splits = splits;
    }

    /** 由调用方持账单锁；当前读不复用生成任务此前建立的旧事务快照。 */
    public boolean hasRefunds(StlBill bill) {
        return DataScopeContext.executeWithoutScope(() -> {
            if (nz(bill.getRefundedGoodsMinor()) > 0 || nz(bill.getReversedMinor()) > 0
                    || StlBill.REVERSED.equals(bill.getStatus())) return true;
            if (!refunds.selectList(Wrappers.<StlBillRefund>lambdaQuery()
                    .eq(StlBillRefund::getSettleNo, bill.getSettleNo()).last("FOR UPDATE")).isEmpty()) return true;
            if (!splits.selectList(Wrappers.<StlSplitLog>lambdaQuery()
                    .eq(StlSplitLog::getSubOrderNo, bill.getSubOrderNo())
                    .in(StlSplitLog::getSplitAction, StlSplitLog.REVERSE, StlSplitLog.SUBSIDY_RETURN)
                    .last("FOR UPDATE")).isEmpty()) return true;
            return !payments.selectList(Wrappers.<StlPayment>lambdaQuery()
                    .eq(StlPayment::getOrderNo, bill.getOrderNo())
                    .in(StlPayment::getDirection, StlPayment.REFUND, StlPayment.SUBSIDY_REVERSE)
                    .notIn(StlPayment::getStatus, StlPayment.FAILED, StlPayment.CLOSED)
                    .and(q -> q.eq(StlPayment::getSubOrderNo, bill.getSubOrderNo())
                            .or().isNull(StlPayment::getSubOrderNo)).last("FOR UPDATE")).isEmpty();
        });
    }

    private static long nz(Long value) { return value == null ? 0 : value; }
}
