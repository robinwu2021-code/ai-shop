package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** 调用方持payTxManager事务；生成与退款事实都先锁原收款，再处理账单/退款行。 */
@Component
public class PaymentSettlementSerialPoint {
    private final PaymentMapper payments;

    public PaymentSettlementSerialPoint(PaymentMapper payments) { this.payments = payments; }

    public void lock(String orderNo) {
        DataScopeContext.executeWithoutScope(() -> payments.selectList(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getOrderNo, orderNo).eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getStatus, StlPayment.SUCCESS).orderByAsc(StlPayment::getId).last("FOR UPDATE")));
    }
}
