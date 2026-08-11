package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.service.AdmissionService;
import ai.neargo.shop.merchant.service.AdmissionService.DepositVO;
import ai.neargo.shop.merchant.service.AdmissionService.TxnVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 弱主体准入（保证金 / 限品类 / 限额，落地清单 F-6）。
 *
 * <p>平台无仓、不碰货，最弱一档（{@code MICRO}）没有「入平台仓让平台验货」这条出路，
 * 只能由平台出钱兜底。这三样是<b>同一套闸门的三个部件</b>，
 * 运营把任何一样调成 0 都会让另外两样失效——所以放在同一个页面上配，不拆开。
 *
 * <p>用 {@code SETTLE_MANAGE} 而不是 {@code MERCHANT_AUDIT}：
 * 这里改的是钱的门槛，与审资质不是同一类权限。
 */
@Profile("ops")
@RestController
@Validated
public class OpsAdmissionController {

    private final AdmissionService admissionService;
    private final AuditLogPort auditLogPort;

    public OpsAdmissionController(AdmissionService admissionService, AuditLogPort auditLogPort) {
        this.admissionService = admissionService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/admission/policies")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public List<MchAdmissionPolicy> policies() {
        return admissionService.policies();
    }

    @PutMapping("/ops/admission/policies/{legalForm}")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public void updatePolicy(@PathVariable String legalForm, @RequestBody MchAdmissionPolicy patch) {
        String operator = SecurityUtils.currentUserNo();
        admissionService.updatePolicy(legalForm, patch, operator);
        // 准入门槛是会被回溯质问的配置（「那单当时为什么放行」），改动必须留痕
        auditLogPort.record("ADMISSION_POLICY_UPDATE", legalForm, operator);
    }

    @GetMapping("/ops/admission/deposits/{merchantNo}")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public DepositVO deposit(@PathVariable String merchantNo) {
        return admissionService.deposit(merchantNo);
    }

    @GetMapping("/ops/admission/deposits/{merchantNo}/txns")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public List<TxnVO> txns(@PathVariable String merchantNo) {
        return admissionService.txns(merchantNo);
    }

    @PostMapping("/ops/admission/deposits/{merchantNo}/txns")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public void addTxn(@PathVariable String merchantNo, @RequestBody TxnReq req) {
        String operator = SecurityUtils.currentUserNo();
        admissionService.recordTxn(merchantNo, req.txnType(),
                req.amountMinor() == null ? 0L : req.amountMinor(), req.reason(), operator);
        auditLogPort.record("DEPOSIT_TXN", merchantNo + ":" + req.txnType(), operator);
    }

    /**
     * 设置某店的收款额度上限。
     *
     * <p><b>阈值必须由服务商确认后再填</b>：系统默认 0（未设置，不拦），
     * 因为写一个猜的数比不写更危险 —— 它会让人以为这件事已经核对过了。
     * 填错方向的后果不对称：填大了等于没设，填小了会把正常商家的货全拦下来。
     */
    @PutMapping("/ops/admission/pay-quota/{merchantNo}")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public void setPayQuota(@PathVariable String merchantNo, @RequestBody QuotaReq req) {
        String operator = SecurityUtils.currentUserNo();
        admissionService.setPayQuotaLimit(merchantNo, req.storeNo(),
                req.quotaLimitMinor() == null ? 0L : req.quotaLimitMinor(), operator);
        auditLogPort.record("PAY_QUOTA_SET", merchantNo + "=" + req.quotaLimitMinor(), operator);
    }

    /** 包装类型而非 long：{@code null} 要能被当成参数缺失报出来，而不是被 Jackson 当成 0 静默通过。 */
    public record TxnReq(String txnType, Long amountMinor, String reason) {
    }

    /** @param storeNo 为空 = 主体级默认收款号 */
    public record QuotaReq(String storeNo, Long quotaLimitMinor) {
    }
}
