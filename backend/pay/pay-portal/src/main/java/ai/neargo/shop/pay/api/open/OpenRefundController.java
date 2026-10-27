package ai.neargo.shop.pay.api.open;

import static ai.neargo.shop.pay.api.open.OpenPaymentController.blank;
import static ai.neargo.shop.pay.api.open.OpenPaymentController.ok;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.api.auth.PortalPrincipal;
import ai.neargo.shop.pay.api.common.PortalResponse;
import ai.neargo.shop.pay.api.open.OpenViews.RefundReq;
import ai.neargo.shop.pay.api.open.OpenViews.RefundView;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.service.AppAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API · 退款（接口清单 §2.1 O06-O07）：scope {@code pay:refund} + 授权动作 REFUND + 应用日退款额度，
 * 之后与商户端同一条退款单流程（可退余额、复核规则、执行器）。
 */
@RestController
@RequestMapping("/open/pay/v1")
public class OpenRefundController {

    private final AppAuthService apps;
    private final RefundService refunds;

    public OpenRefundController(AppAuthService apps, RefundService refunds) {
        this.apps = apps;
        this.refunds = refunds;
    }

    @PostMapping("/orders/{payOrderNo}/refunds")
    public PortalResponse<RefundView> request(PortalPrincipal who, @PathVariable String payOrderNo,
                                              @RequestBody RefundReq req, HttpServletRequest http) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:refund");
        if (blank(req.outRefundNo()) || req.outRefundNo().length() > 64 || req.amountMinor() == null
                || req.amountMinor() <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        apps.checkDailyRefund(app, req.amountMinor());
        return ok(RefundView.of(refunds.request(apps.callerFor(app, "REFUND"), new RefundRequestCmd(payOrderNo,
                req.outRefundNo(), req.outRefundNo(), req.amountMinor(), req.reason()))), http);
    }

    @GetMapping("/refunds/{payRefundNo}")
    public PortalResponse<RefundView> get(PortalPrincipal who, @PathVariable String payRefundNo, HttpServletRequest http) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:read");
        return ok(RefundView.of(refunds.query(apps.callerFor(app, "READ"), payRefundNo)), http);
    }
}
