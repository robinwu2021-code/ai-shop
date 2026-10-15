package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 资金到账事实独立提交；不沿用调用方查单建立的旧RR快照，不对通道受理做此操作。 */
@Component
public class RefundReceiptMutation {
    private final PaymentMapper payments;
    private final BillMapper bills;
    private final PaymentSettlementSerialPoint serial;
    private final SettlementRefundInitializer initializer;

    public RefundReceiptMutation(PaymentMapper payments, BillMapper bills, PaymentSettlementSerialPoint serial,
                                 SettlementRefundInitializer initializer) {
        this.payments = payments;
        this.bills = bills;
        this.serial = serial;
        this.initializer = initializer;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void confirm(String paymentNo) {
        StlPayment row = payment(paymentNo, false);
        if (row == null) return;
        serial.lock(row.getOrderNo());
        row = payment(paymentNo, true);
        if (row == null) return;
        if (!StlPayment.SUCCESS.equals(row.getStatus())) {
            StlPayment patch = new StlPayment();
            patch.setId(row.getId());
            patch.setStatus(StlPayment.SUCCESS);
            patch.setSucceededAt(System.currentTimeMillis());
            // provider退款号由受理回执保存，到账不覆盖跨方向唯一的trade_no。
            DataScopeContext.executeWithoutScope(() -> payments.updateById(patch));
        }
        reconcile(row);
    }

    private StlPayment payment(String paymentNo, boolean lock) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getPaymentNo, paymentNo).eq(StlPayment::getDirection, StlPayment.REFUND)
                .last(lock ? "FOR UPDATE" : "LIMIT 1")));
    }

    private void reconcile(StlPayment refund) {
        if (refund.getSubOrderNo() == null) return;
        StlBill bill = DataScopeContext.executeWithoutScope(() -> bills.selectOne(Wrappers.<StlBill>lambdaQuery()
                .eq(StlBill::getSubOrderNo, refund.getSubOrderNo()).last("FOR UPDATE")));
        if (bill != null) initializer.reconcile(bill);
    }
}
