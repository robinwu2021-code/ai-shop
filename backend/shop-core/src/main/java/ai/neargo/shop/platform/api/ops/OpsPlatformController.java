package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.IndustryService;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.dto.IndustryVO;
import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.dto.OpsVOs.LoginResultVO;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 运营自身与主数据（[API 清单 §4]）。
 *
 * <p>由原 {@code OpsController} 拆出（S7）。那一个类 15 个端点、同时依赖
 * platform / product / trade 三个域——它一个人就能把三个域绑在一起，
 * 谁都别想单独搬走。拆开之后每个域的运营面跟着自己的域走。
 *
 * <p><b>授权只在这一层</b>（{@code @PreAuthorize}），Service 不判功能权限——
 * 把授权散进 Service，同一个业务方法被两个入口调用时就会漏掉一处。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPlatformController {

    private final OpsService opsService;
    private final IndustryService industryService;

    public OpsPlatformController(OpsService opsService, IndustryService industryService) {
        this.opsService = opsService;
        this.industryService = industryService;
    }

    /** 运营登录。唯一免鉴权的 /ops 端点。 */
    @PostMapping("/ops/auth/login")
    public LoginResultVO login(@RequestBody LoginReq req) {
        return opsService.login(req.username(), req.password());
    }

    @GetMapping("/ops/auth/me")
    public StaffVO me() {
        return opsService.me();
    }

    @GetMapping("/ops/staff")
    @PreAuthorize("@perm.can('" + Perms.STAFF_MANAGE + "')")
    public List<StaffVO> staff() {
        return opsService.staffList();
    }

    @GetMapping("/ops/audit-log")
    @PreAuthorize("@perm.can('" + Perms.AUDIT_LOG_VIEW + "')")
    public List<AuditLogVO> auditLogs(@RequestParam(required = false) String target) {
        return opsService.auditLogs(target);
    }

    @GetMapping("/ops/merchant/apply")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public List<MerchantApplyVO> applyQueue() {
        return opsService.applyQueue();
    }

    /**
     * 受理：告诉商家「有人在看了」。不改变审核结果，也不是通过的必经步骤。
     */
    @PostMapping("/ops/merchant/apply/{applyNo}/accept")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public void acceptApply(@PathVariable String applyNo) {
        opsService.acceptApply(applyNo);
    }

    @PostMapping("/ops/merchant/apply/{applyNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public void auditApply(@PathVariable String applyNo, @RequestBody AuditReq req) {
        opsService.auditApply(applyNo, Boolean.TRUE.equals(req.approved()), req.reason(),
                req.serviceScope(), req.communityNos());
    }

    /**
     * 入驻申请检索：能翻历史，不只是待办。
     *
     * <p>「这家店当初是谁批的、为什么驳回」是最常见的一类追溯 ——
     * 只留待办队列的话，这些问题只能去翻审计日志。
     */
    @GetMapping("/ops/merchant/apply/search")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public PageData<MerchantApplyVO> searchApplies(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        return opsService.searchApplies(status, keyword, page, size);
    }

    // ---------------------------------------------------------------- 行业主数据

    /**
     * 行业列表（含停用的）。<b>带 merchantCount</b> —— 运营在改准入之前要知道
     * 这一改会影响多少家店；不带的话，「停用某个行业」就是一次盲操作。
     */
    @GetMapping("/ops/industries")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public List<IndustryVO> industries() {
        return industryService.list();
    }

    /**
     * 改小微准入。<b>只有平台能改</b> —— 它反映的是通道规则，不是商家意愿。
     *
     * <p>必须写 remark：三个月后再看这条记录时，「为什么餐饮能小微而线上不能」
     * 只有当时那个人知道。
     */
    @PostMapping("/ops/industries/{industry}/micro-allowed")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public IndustryVO setMicroAllowed(@PathVariable String industry,
                                      @RequestBody MicroAllowedReq req) {
        IndustryVO vo = industryService.setMicroAllowed(industry, req.payChannel(),
                Boolean.TRUE.equals(req.allowed()), req.remark());
        opsService.audit("INDUSTRY_MICRO", industry,
                req.payChannel() + "=" + req.allowed() + "；" + req.remark());
        return vo;
    }

    /** 启停。<b>只影响新入驻，存量商家不动</b> —— 停用不是撤销资质。 */
    @PostMapping("/ops/industries/{industry}/enabled")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public IndustryVO setIndustryEnabled(@PathVariable String industry,
                                         @RequestBody EnabledReq req) {
        IndustryVO vo = industryService.setEnabled(industry, Boolean.TRUE.equals(req.enabled()));
        opsService.audit("INDUSTRY_ENABLED", industry, String.valueOf(req.enabled()));
        return vo;
    }

    /**
     * 设/取消该行业强制开启积分。<b>只改默认值，不回写存量商家</b> ——
     * 强制开积分要提前 30 天通知 + 费率补偿 + 申诉通道（ADR-006），
     * 一个开关直接改掉所有存量商家的成本结构是不行的。
     */
    @PostMapping("/ops/industries/{industry}/points-forced")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public IndustryVO setPointsForced(@PathVariable String industry,
                                      @RequestBody PointsForcedReq req) {
        IndustryVO vo = industryService.setPointsForced(industry, Boolean.TRUE.equals(req.forced()));
        opsService.audit("INDUSTRY_POINTS", industry, String.valueOf(req.forced()));
        return vo;
    }

    public record MicroAllowedReq(String payChannel, Boolean allowed, String remark) {
    }

    public record EnabledReq(Boolean enabled) {
    }

    public record PointsForcedReq(Boolean forced) {
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * @param serviceScope 通过时补/改服务范围。为空则沿用申请单上的值。
     * @param communityNos 同上。
     *
     *                     <p><b>为什么审核时要能改</b>：商家申请时允许留空（ADR-009），
     *                     但通过时不能空 —— 空的后果是上着架却对谁都不可见，且不报错。
     */
    public record AuditReq(Boolean approved, String reason,
                           String serviceScope, List<String> communityNos) {
    }
}
