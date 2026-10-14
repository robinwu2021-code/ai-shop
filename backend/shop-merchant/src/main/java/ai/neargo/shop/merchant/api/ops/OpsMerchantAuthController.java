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
                                     AuditLogPort auditLogPort,
                                     ai.neargo.shop.archive.ArchiveService archiveService) {
        this.authCodeService = authCodeService;
        this.governService = governService;
        this.auditLogPort = auditLogPort;
        this.archiveService = archiveService;
    }

    private final ai.neargo.shop.archive.ArchiveService archiveService;

    /*
     * 商家归档 = 从运营端默认列表移走，**与封禁完全是两回事**：
     * 封禁改 status，商家立刻停业；归档只改可见性，他照常营业。
     * 挤进同一列的话，「已停业的商家想从列表里收起来」就没法表达。
     */
    @PostMapping("/ops/merchants/{merchantNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public java.util.Map<String, Object> archiveMerchant(@PathVariable String merchantNo) {
        long at = archiveService.archive(ai.neargo.shop.archive.ArchiveService.Kind.MERCHANT,
                merchantNo, ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        return java.util.Map.of("merchantNo", merchantNo, "archivedAt", at);
    }

    @PostMapping("/ops/merchants/{merchantNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public java.util.Map<String, Object> unarchiveMerchant(@PathVariable String merchantNo) {
        archiveService.unarchive(ai.neargo.shop.archive.ArchiveService.Kind.MERCHANT,
                merchantNo, ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        return java.util.Map.of("merchantNo", merchantNo);
    }

    @GetMapping("/ops/merchants/auth-codes")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public List<MerchantAuthCodeService.AuthCodeVO> listCodes() {
        return authCodeService.listCodes();
    }

    @PutMapping("/ops/merchants/{merchantNo}/auth-codes")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_CATEGORY_GRANT + "')")
    public MerchantAuthCodeService.SetResult setCodes(@PathVariable String merchantNo,
                                                      @RequestBody SetReq req) {
        var result = authCodeService.setCodes(merchantNo, req.codes(), req.reason());
        /*
         * 撤了码就把**影响面一并记进审计**：事后追「这家店的货为什么突然上不去了」，
         * 从日志里就能直接答，不必再去比对当时的商品快照。
         */
        String impact = result.revoked().isEmpty() ? ""
                : "｜撤销：" + String.join(",", result.revoked())
                + "，影响在架商品 " + result.affected() + " 件";
        auditLogPort.record("MERCHANT_AUTH_CODES", merchantNo,
                String.join(",", result.codes()) + "｜原因：" + req.reason() + impact);
        return result;
    }

    /** @param reason 改动原因，必填 —— 它决定商家能上架什么 */
    public record SetReq(List<String> codes, String reason) {
    }

    // ---------------------------------------------------------------- 商家治理（P-11.1）

    @GetMapping("/ops/merchants")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public ai.neargo.shop.common.PageData<MerchantGovernService.MerchantProfileVO> merchants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String communityNo,
            @RequestParam(required = false) String keyword,
            // 端上一直在发这两个，此前后端不接 —— 选了档位/开了「显示已归档」都纹丝不动
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) Boolean showArchived,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return governService.list(status, communityNo, keyword, tier, showArchived,
                page, Math.min(size, 100));
    }

    @GetMapping("/ops/merchants/{merchantNo}")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
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
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public MerchantGovernService.MerchantProfileVO setStatus(@PathVariable String merchantNo,
                                                             @RequestBody StatusReq req) {
        var vo = governService.setStatus(merchantNo, req.status(), req.remark(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("MERCHANT_STATUS", merchantNo, req.status() + "：" + req.remark());
        return vo;
    }

    /** 认证标。只给正常经营中且毁约未达上限的商家 —— 它是平台的背书。 */
    @PostMapping("/ops/merchants/{merchantNo}/verified")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_VERIFY_GRANT + "')")
    public MerchantGovernService.MerchantProfileVO setVerified(@PathVariable String merchantNo,
                                                               @RequestBody VerifiedReq req) {
        boolean v = Boolean.TRUE.equals(req.verified());
        var vo = governService.setVerified(merchantNo, v, SecurityUtils.currentUserNo());
        auditLogPort.record("MERCHANT_VERIFIED", merchantNo, v ? "授予认证标" : "撤销认证标");
        return vo;
    }

    @GetMapping("/ops/merchants/violations")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public ai.neargo.shop.common.PageData<MerchantGovernService.ViolationVO> violations(
            @RequestParam(required = false) String merchantNo,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(governService.violations(merchantNo), page, size);
    }

    /**
     * 记一条违规处置。副作用是处置的一部分：
     * {@code BREACH} 累加毁约次数（报价卡上公示），{@code SUSPEND} 真的封店，
     * {@code STORE_OFFLINE} 真的把那家门店压到 SUSPENDED 并撤下货架（V96）。
     */
    @PostMapping("/ops/merchants/{merchantNo}/violations")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public MerchantGovernService.ViolationVO recordViolation(@PathVariable String merchantNo,
                                                             @RequestBody ViolationReq req) {
        var vo = governService.recordViolation(merchantNo, req.storeNo(), req.type(), req.action(),
                req.detail(), SecurityUtils.currentUserNo());
        // 门店级处置是不可逆的状态变更，critical 留痕
        auditLogPort.record("MERCHANT_VIOLATION", merchantNo,
                (req.storeNo() == null ? "" : "门店 " + req.storeNo() + "｜")
                        + req.type() + "/" + req.action() + "：" + req.detail(),
                ai.neargo.shop.merchant.entity.MchViolation.STORE_OFFLINE.equals(req.action()));
        return vo;
    }

    /** @param remark 处置说明。**封禁与冻结必填** —— 商家只看到「店没了」是不行的 */
    public record StatusReq(String status, String remark) {
    }

    public record VerifiedReq(Boolean verified) {
    }

    /**
     * @param type    FAKE_GOODS / BREACH / PRICE_FRAUD / SERVICE
     * @param action  WARN / LIMIT / SUSPEND / STORE_OFFLINE
     * @param storeNo 门店级处置（STORE_OFFLINE）必填，其余动作必须为空
     */
    public record ViolationReq(String type, String action, String detail, String storeNo) {
    }

    // ---------------------------------------------------------------- 门面内容审核（P-10.1）

    /**
     * 待人审的店招 / 公告。
     *
     * <p>队列里只有**机审命中**的内容 —— 没命中的直接生效了，不进这里。
     * 全部先审后发的话，「今日到货」这类时效内容要等几小时，等于功能没用。
     */
    @GetMapping("/ops/stores/audits")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public ai.neargo.shop.common.PageData<MerchantGovernService.StoreAuditVO> storeAudits(
            @RequestParam(required = false) String status,
            // 端上的搜索框与「内容类型」下拉一直在发这两个，此前后端不声明 ——
            // 于是点了没反应、也不报错，看起来像「这一页的筛选坏了」
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(
                governService.storeAudits(status, kind, keyword), page, size);
    }

    /** 裁决。通过 → 内容这时才生效；驳回 → 必须写原因，它原样出现在商家 B 端。 */
    @PostMapping("/ops/stores/audits/{auditNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public MerchantGovernService.StoreAuditVO decideStoreAudit(@PathVariable String auditNo,
                                                               @RequestBody StoreAuditDecideReq req) {
        boolean pass = Boolean.TRUE.equals(req.pass());
        var vo = governService.decideStoreAudit(auditNo, pass, req.reason(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("STORE_CONTENT_DECIDE", auditNo,
                (pass ? "通过" : "驳回：" + req.reason()));
        return vo;
    }

    public record StoreAuditDecideReq(Boolean pass, String reason) {
    }
}
