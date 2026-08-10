package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.merchant.service.MerchantAuthCodeService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 商家类目经营授权。
 *
 * <p>它是 {@code prd_category.required_code} 那道门槛的**发证一侧**。
 * ops-web 早已有完整的授权页与 mock 规则，后端一直缺 ——
 * 缺的后果不是「少个功能」，而是挂了门槛的类目**永远拒绝所有人**。
 */
@Profile("ops")
@RestController
@Validated
public class OpsMerchantAuthController {

    private final MerchantAuthCodeService authCodeService;
    private final AuditLogPort auditLogPort;

    public OpsMerchantAuthController(MerchantAuthCodeService authCodeService, AuditLogPort auditLogPort) {
        this.authCodeService = authCodeService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/merchants/auth-codes")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public List<MerchantAuthCodeService.AuthCodeVO> listCodes() {
        return authCodeService.listCodes();
    }

    @PutMapping("/ops/merchants/{merchantNo}/auth-codes")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public List<String> setCodes(@PathVariable String merchantNo, @RequestBody SetReq req) {
        var codes = authCodeService.setCodes(merchantNo, req.codes(), req.reason());
        auditLogPort.record("MERCHANT_AUTH_CODES", merchantNo,
                String.join(",", codes) + "｜原因：" + req.reason());
        return codes;
    }

    /** @param reason 改动原因，必填 —— 它决定商家能上架什么 */
    public record SetReq(List<String> codes, String reason) {
    }
}
