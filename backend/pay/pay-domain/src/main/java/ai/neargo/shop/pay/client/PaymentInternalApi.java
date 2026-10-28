package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.pay.InternalHeaders;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.pay.PaymentPort.PaymentHandle;
import ai.neargo.shop.spi.pay.PaymentPort.RefundHandle;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/** {@link PaymentInternalPaths} 的 Http Interface。调用方身份逐次放在头里（同一进程替商城与餐饮各调各的） */
public interface PaymentInternalApi {

    // 每个方法的 source = CallSourceCodec 编好的来源头：命令对象里的来源不进请求体（PaymentPort M2.2），只走这个头

    @PostExchange(PaymentInternalPaths.PAYMENTS)
    PortResult<PaymentHandle> createPayment(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                            @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                            @RequestHeader(InternalHeaders.CALL_SOURCE) String source,
                                            @RequestBody InternalCreatePaymentCmd cmd);

    @PostExchange(PaymentInternalPaths.PAYMENT_QUERY)
    PortResult<PaymentHandle> queryPayment(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                           @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                           @RequestHeader(InternalHeaders.CALL_SOURCE) String source,
                                           @RequestBody Query query);

    @PostExchange(PaymentInternalPaths.PAYMENT_CLOSE)
    PortResult<PaymentHandle> closePayment(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                           @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                           @RequestHeader(InternalHeaders.CALL_SOURCE) String source,
                                           @RequestBody Close close);

    @PostExchange(PaymentInternalPaths.REFUNDS)
    PortResult<RefundHandle> requestRefund(@RequestHeader(PaymentInternalPaths.CALLER) String caller,
                                           @RequestHeader(PaymentInternalPaths.CREDENTIAL) String credential,
                                           @RequestHeader(InternalHeaders.CALL_SOURCE) String source,
                                           @RequestBody InternalRefundCmd cmd);

    record Query(String bizType, String payOrderNo) {
    }

    record Close(String bizType, String outOrderNo, String reason) {
    }
}
