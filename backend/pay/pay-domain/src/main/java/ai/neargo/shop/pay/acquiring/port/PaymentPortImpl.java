package ai.neargo.shop.pay.acquiring.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.PaymentPort;
import java.time.Instant;

/**
 * {@link PaymentPort} 的内嵌实现（代码清单 §5.2）：按 {@code bizType} 映射内部 app → {@link InternalCallers} 造调用方 →
 * {@link PaymentService}。装配在 shop-app {@code OrdinaryPaymentRouting}（与远程实现同一时刻只装一个；支付域不读形态开关）。
 */
public class PaymentPortImpl implements PaymentPort {

    private final PaymentService payments;
    private final RefundService refunds;
    private final InternalCallers callers;
    private final boolean enabled;

    public PaymentPortImpl(PaymentService payments, RefundService refunds, InternalCallers callers, boolean enabled) {
        this.payments = payments;
        this.refunds = refunds;
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
    public PaymentHandle queryPayment(String bizType, String payOrderNo, CallSource source) {
        if (!enabled) {
            return null;
        }
        PayOrder o = payments.find(payOrderNo);
        if (o == null || !appOf(bizType).equals(appOf(o.getBizType()))) {
            // 别的内部调用方的单当作没有：餐饮查不到商城的支付单
            return null;
        }
        return handle(payments.query(callers.system(appOf(bizType), o.getStoreId()), payOrderNo));
    }

    @Override
    public PaymentHandle closePayment(String bizType, String outOrderNo, String reason, CallSource source) {
        if (!enabled) {
            return null;
        }
        PayOrder o = payments.findByOutOrderNo(callers.appId(appOf(bizType)), outOrderNo);
        if (o == null) {
            return null;
        }
        return handle(payments.close(callers.system(appOf(bizType), o.getStoreId()), o.getPayOrderNo(), reason));
    }

    @Override
    public RefundHandle requestRefund(InternalRefundCmd c) {
        if (!enabled) {
            return null;
        }
        PayOrder o = payments.find(c.payOrderNo());
        if (o == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        // 商家操作员身份（BIZ），不是系统身份：没有操作员在这里就被拒
        PayRefund r = refunds.request(callers.merchantOperator(appOf(c.bizType()), o.getStoreId(), c.operatorNo()),
                new RefundRequestCmd(c.payOrderNo(), c.outRefundNo(), c.bizRefundNo(), c.amountMinor(), c.reason()));
        return new RefundHandle(r.getPayRefundNo(), r.getStatus(), r.getFailedReason());
    }

    static String appOf(String bizType) {
        return PaymentPort.FOOD_CHECK.equals(bizType) ? InternalCallers.FOOD : InternalCallers.MALL;
    }

    private static PaymentHandle handle(PayOrder o) {
        return new PaymentHandle(o.getId(), o.getPayOrderNo(), o.getStatus());
    }
}
