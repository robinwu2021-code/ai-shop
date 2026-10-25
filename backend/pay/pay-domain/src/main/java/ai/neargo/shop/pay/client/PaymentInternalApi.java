package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.pay.PaymentPort.PaymentHandle;
import ai.neargo.shop.spi.pay.PaymentPort.RefundHandle;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/** {@link PaymentInternalPaths} 的 Http Interface。调用方身份逐次放在头里（同一进程替商城与餐饮各调各的） */
public interface PaymentInternalApi {

    @PostExchange(PaymentInternalPaths.PAYMENTS)
    PortResult<PaymentHandle> createPayment(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                            @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                            @RequestBody InternalCreatePaymentCmd cmd);

    @PostExchange(PaymentInternalPaths.PAYMENT_QUERY)
    PortResult<PaymentHandle> queryPayment(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                           @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                           @RequestBody Query query);

    @PostExchange(PaymentInternalPaths.PAYMENT_CLOSE)
    PortResult<PaymentHandle> closePayment(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                           @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                           @RequestBody Close close);

    @PostExchange(PaymentInternalPaths.REFUNDS)
    PortResult<RefundHandle> requestRefund(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                           @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                           @RequestBody InternalRefundCmd cmd);

    record Query(String bizType, String payOrderNo) {
    }

    record Close(String bizType, String outOrderNo, String reason) {
    }
}
