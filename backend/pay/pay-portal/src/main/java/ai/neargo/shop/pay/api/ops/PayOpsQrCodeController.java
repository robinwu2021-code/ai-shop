package ai.neargo.shop.pay.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.checkout.service.QrCodeService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 静态码风控停用（F57）：任何门店的码运营都能停 —— 被盗用、被换贴时等不及商家自己来停 */
@Profile("ops")
@RestController
@RequestMapping("/pay-ops/v1/qr-codes")
public class PayOpsQrCodeController {

    private final QrCodeService codes;
    private final InternalCallers callers;

    public PayOpsQrCodeController(QrCodeService codes, InternalCallers callers) {
        this.codes = codes;
        this.callers = callers;
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_QR_MANAGE + "')")
    @PutMapping("/{qrCode}/disabled")
    public void disable(@PathVariable String qrCode) {
        codes.disable(callers.opsOperator(SecurityUtils.currentUserNo()), qrCode);
    }
}
