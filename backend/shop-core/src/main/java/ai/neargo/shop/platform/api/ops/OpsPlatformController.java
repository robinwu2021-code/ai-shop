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
import ai.neargo.shop.platform.OpsService.CreatedStaffVO;
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
    private final ai.neargo.shop.platform.ServiceScopeAdminService serviceScopeAdminService;

    public OpsPlatformController(OpsService opsService, IndustryService industryService,
                                 ai.neargo.shop.platform.ServiceScopeAdminService serviceScopeAdminService) {
        this.opsService = opsService;
        this.industryService = industryService;
        this.serviceScopeAdminService = serviceScopeAdminService;
    }

    /** 运营登录。唯一免鉴权的 /ops 端点。 */
    @PostMapping("/ops/auth/login")
    public LoginResultVO login(@RequestBody LoginReq req) {
        return opsService.login(req.username(), req.password());
    }

    /**
     * 忘记密码：往登录名那个邮箱发一次性重置码。
     *
     * <p><b>免鉴权</b>（忘了密码自然登不进来），且**无论账号存不存在都返回成功** ——
     * 区分开等于送了个账号探测器，而运营账号的价值远高于普通用户。
     */
    @PostMapping("/ops/auth/forgot")
    public void forgot(@RequestBody ForgotReq req) {
        opsService.forgotPassword(req.username());
    }

    /** 用邮件里的重置码设新密码。免鉴权，安全性全靠那个一次性令牌。 */
    @PostMapping("/ops/auth/reset")
    public void reset(@RequestBody ResetReq req) {
        opsService.resetPassword(req.token(), req.newPassword());
    }

    public record ForgotReq(@NotBlank String username) {
    }

    /** @param newPassword 至少 8 位 */
    public record ResetReq(@NotBlank String token, @NotBlank String newPassword) {
    }

    @GetMapping("/ops/auth/me")
    public StaffVO me() {
        return opsService.me();
    }

    /**
     * 运营员工列表。
     *
     * <p><b>路径是复数</b>：此前后端写 {@code /ops/staff}、ops-web 调 {@code /ops/staffs}，
     * 两边从来没通过。而失败的样子不是报错 —— 列表页拿不到数据渲染成空表，
     * 与「一个员工都没有」长得一模一样。与 {@code /ops/merchants}、
     * {@code /ops/pickups} 统一到复数。
     *
     * <p>包 {@link PageData} 而不是返回裸 {@code List}：前端按分页渲染，
     * 裸数组会让它读不到 {@code records} —— 又是一次「接口 200、页面空白」。
     * 员工是几十条量级的主数据，全量算完再切页即可。
     */
    @GetMapping("/ops/staffs")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_READ + "')")
    public PageData<StaffVO> staff(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long size,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String role,
                                   @RequestParam(required = false) String enabled) {
        /*
         * **实测发现的缺陷**：这三个参数此前压根不在方法签名里 —— ops-web 的搜索框、
         * 角色筛选、状态筛选一直在发请求，Spring 静默丢弃它不认识的 query 参数，
         * 后端原样返回全量列表。界面上看是「搜了没反应」，不报错、不提示，
         * 是这个仓库最难查的一类缺陷：入口在、请求在，就是不生效。
         *
         * 过滤放在 controller 而不是 service：staffList() 就是几十条量级的全量查询，
         * 不值得为三个可选条件在 mapper 层拼动态 SQL。
         */
        List<StaffVO> all = opsService.staffList();
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        List<StaffVO> filtered = all.stream()
                .filter(s -> kw == null || kw.isEmpty()
                        || s.staffNo().toLowerCase().contains(kw)
                        || s.username().toLowerCase().contains(kw)
                        || s.realName().toLowerCase().contains(kw))
                .filter(s -> role == null || role.isBlank() || s.roles().contains(role))
                .filter(s -> enabled == null || enabled.isBlank()
                        || ("1".equals(enabled)) == "ACTIVE".equals(s.status()))
                .toList();
        return PageData.ofAll(filtered, page, size);
    }

    /**
     * 启停员工。停用会**立刻踢掉在线会话** —— 只改库里的状态，
     * 已经登录的人在 token 过期前照常操作，而按下停用的那个人以为生效了。
     */
    @PostMapping("/ops/staffs/{staffNo}/enabled")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_UPDATE + "')")
    public StaffVO setStaffEnabled(@PathVariable String staffNo, @RequestBody EnabledReq req) {
        return opsService.setStaffEnabled(staffNo, Boolean.TRUE.equals(req.enabled()));
    }

    /**
     * 新建员工。**返回一次性初始密码**，之后再也取不到。
     *
     * <p>密码由后端生成而不是让界面传：收明文的问题不是加密与否，
     * 是谁都能在 devtools 里看到刚给同事设的密码，而且它会顺着请求体进日志。
     */
    @PostMapping("/ops/staffs")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_UPDATE + "')")
    public CreatedStaffVO createStaff(@RequestBody CreateStaffReq req) {
        return opsService.createStaff(req.username(), req.realName(), req.roles());
    }

    /**
     * 改角色（<b>多角色</b>）。权限取并集。
     *
     * <p>角色码必须在库里真实存在，否则这个账号 perms 为空 ——
     * 能登录、导航全空、看不出原因。
     */
    @PostMapping("/ops/staffs/{staffNo}/roles")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_UPDATE + "')")
    public StaffVO setStaffRoles(@PathVariable String staffNo, @RequestBody RolesReq req) {
        return opsService.setStaffRoles(staffNo, req.roles());
    }

    /**
     * 改角色（单角色，<b>已弃用</b>）。
     *
     * <p>转调多角色版。**不立刻删**：并行会话的前端可能还在调它，
     * 删掉的表现是「改角色按钮 404」而不是一条清楚的报错。
     */
    @Deprecated(forRemoval = true)
    @PostMapping("/ops/staffs/{staffNo}/role")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_UPDATE + "')")
    public StaffVO setStaffRole(@PathVariable String staffNo, @RequestBody RoleReq req) {
        return opsService.setStaffRoles(staffNo, java.util.List.of(req.role()));
    }

    /** 改自己的密码。首登被 mustChangePassword 卡住时也走这条。 */
    @PostMapping("/ops/staffs/me/password")
    public void changeOwnPassword(@RequestBody ChangePasswordReq req) {
        opsService.changeOwnPassword(req.oldPassword(), req.newPassword());
    }

    /**
     * 配数据域。空 = 不限定。
     *
     * <p><b>已生效的范围</b>（2026-08-14，TDD-运营端数据域接入 批①）：
     * 订单检索/详情/异常队列/兄弟单，以及运营看板上所有基于子订单的口径 ——
     * 它们走 {@code ord_sub_order}，四个维度锚点齐（SELF/MERCHANT/COMMUNITY/PICKUP）。
     *
     * <p><b>还没接的</b>：商品池与 SKU（批③）、结算与履约（批④）、门店与主体档案（批②，
     * 卡在「门店属于哪个社区」没有单值表示，见 TDD §六 Q1 的实施记录）。
     * 那几处仍是全量 —— 配了数据域的人在那些页面上看到的仍是全平台。
     *
     * <p>写路径**刻意不受数据域约束**（§5 T2）：越权由 {@code @PreAuthorize} +
     * Service 内归属校验挡。写路径也走数据域会把「处置域外主体」变成静默失败。
     */
    @PostMapping("/ops/staffs/{staffNo}/scope")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_UPDATE + "')")
    public StaffVO setStaffScope(@PathVariable String staffNo, @RequestBody ScopeReq req) {
        return opsService.setStaffScope(staffNo, req.merchantNo(), req.communityNo(), req.pickupNo());
    }

    public record CreateStaffReq(String username, String realName, java.util.List<String> roles) {
    }

    public record RolesReq(java.util.List<String> roles) {
    }

    public record ChangePasswordReq(String oldPassword, String newPassword) {
    }

    public record RoleReq(String role) {
    }

    public record ScopeReq(String merchantNo, String communityNo, String pickupNo) {
    }

    @GetMapping("/ops/audit-log")
    @PreAuthorize("@perm.can('" + Perms.IAM_AUDIT_READ + "')")
    public PageData<AuditLogVO> auditLogs(@RequestParam(required = false) String target,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Boolean critical,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long size) {
        return opsService.auditLogs(target, keyword, critical, page, size);
    }

    @GetMapping("/ops/merchant/apply")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_AUDIT + "')")
    public List<MerchantApplyVO> applyQueue() {
        return opsService.applyQueue();
    }

    /**
     * 受理：告诉商家「有人在看了」。不改变审核结果，也不是通过的必经步骤。
     */
    @PostMapping("/ops/merchant/apply/{applyNo}/accept")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_AUDIT + "')")
    public void acceptApply(@PathVariable String applyNo) {
        opsService.acceptApply(applyNo);
    }

    @PostMapping("/ops/merchant/apply/{applyNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_AUDIT + "')")
    public void auditApply(@PathVariable String applyNo, @RequestBody AuditReq req) {
        opsService.auditApply(applyNo, Boolean.TRUE.equals(req.approved()), req.reason(),
                req.serviceScope(), req.communityNos(), req.grantCodes());
    }

    /**
     * 入驻申请检索：能翻历史，不只是待办。
     *
     * <p>「这家店当初是谁批的、为什么驳回」是最常见的一类追溯 ——
     * 只留待办队列的话，这些问题只能去翻审计日志。
     */
    @GetMapping("/ops/merchant/apply/search")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_AUDIT + "')")
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
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_READ + "')")
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
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_UPDATE + "')")
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
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_UPDATE + "')")
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
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_UPDATE + "')")
    public IndustryVO setPointsForced(@PathVariable String industry,
                                      @RequestBody PointsForcedReq req) {
        IndustryVO vo = industryService.setPointsForced(industry, Boolean.TRUE.equals(req.forced()));
        opsService.audit("INDUSTRY_POINTS", industry, String.valueOf(req.forced()));
        return vo;
    }

    // ---------------------------------------------------------------- 经营范围（ADR-009）

    /**
     * 三档全量，带启用状态与在用商家数。
     *
     * <p>与行业列表同一个标准：<b>带影响面计数</b>。关掉一档而不知道有多少家店在用，
     * 是一次盲操作 —— 关 CITY 和关一个没人用的档，界面上看起来完全一样。
     */
    @GetMapping("/ops/service-scopes")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_READ + "')")
    public List<ai.neargo.shop.platform.ServiceScopeAdminService.ServiceScopeVO> serviceScopes() {
        return serviceScopeAdminService.list();
    }

    /**
     * 开关某一档。<b>只影响新的写入</b>，存量商家已选的档不动 ——
     * 与行业停用同一口径：停用不是撤销。
     *
     * <p>一期自营模式关掉了 PLATFORM（没有虚拟商品/卡券/自营快递品支撑它）。
     * 拿到 EDI 切平台模式时在这里打开，不用改代码、不用发版 —— 这正是这个开关存在的理由。
     */
    @PostMapping("/ops/service-scopes/{scope}/enabled")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_INDUSTRY_UPDATE + "')")
    public List<ai.neargo.shop.platform.ServiceScopeAdminService.ServiceScopeVO> setServiceScopeEnabled(
            @PathVariable String scope, @RequestBody ScopeEnabledReq req) {
        var vos = serviceScopeAdminService.setEnabled(scope, Boolean.TRUE.equals(req.enabled()), req.reason());
        opsService.audit("SERVICE_SCOPE_ENABLED", scope, req.enabled() + "｜原因：" + req.reason());
        return vos;
    }

    public record MicroAllowedReq(String payChannel, Boolean allowed, String remark) {
    }

    /** @param reason 必填 —— 关掉一档等于把一类商家挡在门外 */
    public record ScopeEnabledReq(Boolean enabled, String reason) {
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
    /**
     * @param grantCodes 通过时授予的经营类目编码。空 = 沿用申请单上已定下的那份；
     *                   两边都空 = 只经营无门槛类目（合法）
     */
    public record AuditReq(Boolean approved, String reason,
                           String serviceScope, List<String> communityNos,
                           List<String> grantCodes) {
    }
}
