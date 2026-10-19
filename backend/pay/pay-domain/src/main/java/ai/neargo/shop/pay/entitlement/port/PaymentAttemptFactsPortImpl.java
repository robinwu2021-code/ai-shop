package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** A failed or closed charge is still a payment attempt and prevents retroactive repricing. */
@Component
public class PaymentAttemptFactsPortImpl implements PaymentAttemptFactsPort {
    private final PaymentMapper payments;
    public PaymentAttemptFactsPortImpl(PaymentMapper payments) { this.payments = payments; }
    @Override public boolean hasAnyAttempt(String tenantNo, String orderNo) {
        return payments.selectCount(Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getTenantNo, tenantNo)
                .eq(StlPayment::getOrderNo, orderNo).eq(StlPayment::getDirection, StlPayment.PAY)) > 0;
    }
}
