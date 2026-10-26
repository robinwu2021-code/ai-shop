package ai.neargo.shop.pay.api.checkout;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentResult;
import ai.neargo.shop.pay.api.common.PortalRequests;
import ai.neargo.shop.pay.api.common.PortalResponse;
import ai.neargo.shop.pay.checkout.service.CheckoutService;
import ai.neargo.shop.pay.checkout.service.CheckoutService.CodeView;
import ai.neargo.shop.pay.checkout.service.CheckoutService.Issued;
import ai.neargo.shop.pay.checkout.service.CheckoutService.SessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收银台 API（接口清单 §3.5；TDD-支付服务-I5接入层 S7 F30-F34）。顾客完全不受信：这里不接受任何金额以外的业务入参，
 * 金额也只有静态码一处可输；其余一律来自服务端。
 */
@RestController
@RequestMapping("/checkout/v1")
public class CheckoutController {

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    record PayReq(String payMethod, String payerOpenId, String payerAppId) {
    }

    record CodeOrderReq(Long amountMinor) {
    }

    @GetMapping("/sessions/{token}")
    public PortalResponse<SessionView> session(@PathVariable String token, HttpServletRequest http) {
        return ok(checkout.view(token), http);
    }

    @PostMapping("/sessions/{token}/pay")
    public PortalResponse<StartPaymentResult> pay(@PathVariable String token, @RequestBody PayReq req,
                                                  HttpServletRequest http) {
        return ok(checkout.pay(token, req.payMethod(), req.payerOpenId(), req.payerAppId()), http);
    }

    @GetMapping("/sessions/{token}/result")
    public PortalResponse<Map<String, String>> result(@PathVariable String token, HttpServletRequest http) {
        return ok(Map.of("result", checkout.result(token)), http);
    }

    @GetMapping("/codes/{qrCode}")
    public PortalResponse<CodeView> code(@PathVariable String qrCode, HttpServletRequest http) {
        return ok(checkout.code(qrCode), http);
    }

    @PostMapping("/codes/{qrCode}/orders")
    public PortalResponse<Issued> orderByCode(@PathVariable String qrCode, @RequestBody CodeOrderReq req,
                                              HttpServletRequest http) {
        if (req.amountMinor() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return ok(checkout.orderByCode(qrCode, req.amountMinor()), http);
    }

    private static <T> PortalResponse<T> ok(T data, HttpServletRequest http) {
        return new PortalResponse<>(PortalResponse.OK, "OK", data, PortalRequests.requestId(http));
    }
}
