package ai.neargo.shop.pay.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.port.PaymentPortImpl;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.client.PaymentInternalApi;
import ai.neargo.shop.pay.client.PaymentInternalPaths;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.pay.client.port.RemotePaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付单内部端点（开发计划 §3.1 独立形态；TDD-支付服务-I3支付单退款流水 S8）：建单、查单、关单、退款 → 与内嵌同一个
 * {@link PaymentPortImpl}。调用方已由 {@link InternalCallerFilter} 认出；这里只放行<b>它自己的业务类型</b> ——
 * 餐饮调不了商城的退款（403）。业务拒绝走 {@link PortResult}，不经全局响应包装。
 */
@RestController
public class InternalPaymentEndpoint {

    private final PaymentPort payments;

    public InternalPaymentEndpoint(PaymentService payments, RefundService refunds, InternalCallers callers) {
        this.payments = new PaymentPortImpl(payments, refunds, callers, true);
    }

    @PostMapping(PaymentInternalPaths.PAYMENTS)
    public ResponseEntity<?> create(@RequestBody InternalCreatePaymentCmd cmd, HttpServletRequest request) {
        return own(request, cmd.bizType(), () -> payments.createPayment(cmd));
    }

    @PostMapping(PaymentInternalPaths.PAYMENT_QUERY)
    public ResponseEntity<?> query(@RequestBody PaymentInternalApi.Query q, HttpServletRequest request) {
        return own(request, q.bizType(), () -> payments.queryPayment(q.bizType(), q.payOrderNo()));
    }

    @PostMapping(PaymentInternalPaths.PAYMENT_CLOSE)
    public ResponseEntity<?> close(@RequestBody PaymentInternalApi.Close c, HttpServletRequest request) {
        return own(request, c.bizType(), () -> payments.closePayment(c.bizType(), c.outOrderNo(), c.reason()));
    }

    @PostMapping(PaymentInternalPaths.REFUNDS)
    public ResponseEntity<?> refund(@RequestBody InternalRefundCmd cmd, HttpServletRequest request) {
        return own(request, cmd.bizType(), () -> payments.requestRefund(cmd));
    }

    private static <T> ResponseEntity<?> own(HttpServletRequest request, String bizType, Supplier<T> call) {
        Object caller = request.getAttribute(InternalCallerFilter.ATTRIBUTE);
        if (caller == null || !Objects.equals(caller, RemotePaymentPort.appOf(bizType))) {
            return ResponseEntity.status(403).build();
        }
        try {
            return ResponseEntity.ok(PortResult.ok(call.get()));
        } catch (BizException rejected) {
            return ResponseEntity.ok(PortResult.rejected(rejected.errorCode().name(),
                    Arrays.stream(rejected.args() == null ? new Object[0] : rejected.args()).map(String::valueOf).toList()));
        }
    }
}
