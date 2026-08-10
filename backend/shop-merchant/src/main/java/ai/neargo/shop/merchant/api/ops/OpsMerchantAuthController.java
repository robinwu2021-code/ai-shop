package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.MerchantAuthCodeService;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MerchantGovernService governService;
    private final AuditLogPort auditLogPort;

    public OpsMerchantAuthController(MerchantAuthCodeService authCodeService,
                                     MerchantGovernService governService,
                                     AuditLogPort auditLogPort) {
        this.authCodeService = authCodeService;
        this.governService = governService;
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

    // ---------------------------------------------------------------- 商家治理（P-11.1）

    @GetMapping("/ops/merchants")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public List<MerchantGovernService.MerchantProfileVO> merchants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String communityNo,
            @RequestParam(required = false) String keyword) {
        return governService.list(status, communityNo, keyword);
    }

    @GetMapping("/ops/merchants/{merchantNo}")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public MerchantGovernService.MerchantProfileVO merchant(@PathVariable String merchantNo) {
        return governService.detail(merchantNo);
    }

    /**
     * 改**经营状态**（ACTIVE / SUSPENDED / FROZEN）。
     *
     * <p>不是审核 —— 受理/通过/驳回走 {@code /ops/merchant/apply/{applyNo}/audit}。
     * 两者曾经在 ops-web 上合成一个字段，已拆开。
     */
    @PostMapping("/ops/merchants/{merchantNo}/status")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public MerchantGovernService.MerchantProfileVO setStatus(@PathVariable String merchantNo,
                                                             @RequestBody StatusReq req) {
        var vo = governService.setStatus(merchantNo, req.status(), req.remark(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("MERCHANT_STATUS", merchantNo, req.status() + "：" + req.remark());
        return vo;
    }

    /** 认证标。只给正常经营中且毁约未达上限的商家 —— 它是平台的背书。 */
    @PostMapping("/ops/merchants/{merchantNo}/verified")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public MerchantGovernService.MerchantProfileVO setVerified(@PathVariable String merchantNo,
                                                               @RequestBody VerifiedReq req) {
        boolean v = Boolean.TRUE.equals(req.verified());
        var vo = governService.setVerified(merchantNo, v, SecurityUtils.currentUserNo());
        auditLogPort.record("MERCHANT_VERIFIED", merchantNo, v ? "授予认证标" : "撤销认证标");
        return vo;
    }

    @GetMapping("/ops/merchants/violations")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public List<MerchantGovernService.ViolationVO> violations(
            @RequestParam(required = false) String merchantNo) {
        return governService.violations(merchantNo);
    }

    /**
     * 记一条违规处置。两个副作用是处置的一部分：
     * {@code BREACH} 累加毁约次数（报价卡上公示），{@code SUSPEND} 真的封店。
     */
    @PostMapping("/ops/merchants/{merchantNo}/violations")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public MerchantGovernService.ViolationVO recordViolation(@PathVariable String merchantNo,
                                                             @RequestBody ViolationReq req) {
        var vo = governService.recordViolation(merchantNo, req.type(), req.action(), req.detail(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("MERCHANT_VIOLATION", merchantNo,
                req.type() + "/" + req.action() + "：" + req.detail());
        return vo;
    }

    /** @param remark 处置说明。**封禁与冻结必填** —— 商家只看到「店没了」是不行的 */
    public record StatusReq(String status, String remark) {
    }

    public record VerifiedReq(Boolean verified) {
    }

    /**
     * @param type   FAKE_GOODS / BREACH / PRICE_FRAUD / SERVICE
     * @param action WARN / LIMIT / SUSPEND
     */
    public record ViolationReq(String type, String action, String detail) {
    }
}
