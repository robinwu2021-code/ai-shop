package ai.neargo.shop.pay.acquiring.port;

import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.spi.pay.PaymentPort;
import java.time.Instant;

/**
 * {@link PaymentPort} 的内嵌实现（代码清单 §5.2）：按 {@code bizType} 映射内部 app → {@link InternalCallers} 造调用方 →
 * {@link PaymentService}。装配在 shop-app {@code OrdinaryPaymentRouting}（与远程实现同一时刻只装一个；支付域不读形态开关）。
 */
public class PaymentPortImpl implements PaymentPort {

    private final PaymentService payments;
    private final InternalCallers callers;
    private final boolean enabled;

    public PaymentPortImpl(PaymentService payments, InternalCallers callers, boolean enabled) {
        this.payments = payments;
        this.callers = callers;
        this.enabled = enabled;
    }

    @Override
    public PaymentHandle createPayment(InternalCreatePaymentCmd c) {
        if (!enabled) {
            return null;
        }
        PayOrder o = payments.create(callers.system(appOf(c.bizType()), c.storeId()),
                new CreatePaymentCmd(c.bizType(), c.bizNo(), c.outOrderNo(), c.storeId(), c.storeName(), c.entityName(),
                        c.market(), c.amountMinor(), c.currency(), c.subject(), "ONLINE", Instant.ofEpochMilli(c.expireAt()),
                        c.fundsPath()));
        return handle(o);
    }

    @Override
    public PaymentHandle queryPayment(String payOrderNo) {
        if (!enabled) {
            return null;
        }
        PayOrder o = payments.find(payOrderNo);
        return o == null ? null : handle(payments.query(callers.system(appOf(o.getBizType()), o.getStoreId()), payOrderNo));
    }

    static String appOf(String bizType) {
        return PaymentPort.FOOD_CHECK.equals(bizType) ? InternalCallers.FOOD : InternalCallers.MALL;
    }

    private static PaymentHandle handle(PayOrder o) {
        return new PaymentHandle(o.getId(), o.getPayOrderNo(), o.getStatus());
    }
}
