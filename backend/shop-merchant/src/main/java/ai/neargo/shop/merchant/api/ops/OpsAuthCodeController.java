package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.merchant.service.AuthCodeAdminService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 授权码<b>字典</b>维护。
 *
 * <p>权限用 {@link Perms#CATEGORY_MANAGE} 而不是 {@code MERCHANT_AUDIT}：
 * 授权码与 {@code prd_category.required_code} 是同一个机制的两半，
 * <b>定义门槛</b>的人（商品运营）和<b>发证</b>的人（BD）必须分开 ——
 * 否则 BD 遇到一家没资质的店，可以直接把那个码的资质要求删掉，
 * 而这一改影响的是全平台所有商家，审计里看起来却只是一次「改了个字典」。
 *
 * <p>与 {@code GET /ops/merchants/auth-codes}（发证时的可选项，只给启用的）
 * 并存不是重复：两个端点服务两个场景，一个要看得见停用的，一个绝不能发出停用的。
 */
@Profile("ops")
@RestController
@Validated
public class OpsAuthCodeController {

    private final AuthCodeAdminService adminService;
    private final AuditLogPort auditLogPort;

    public OpsAuthCodeController(AuthCodeAdminService adminService, AuditLogPort auditLogPort) {
        this.adminService = adminService;
        this.auditLogPort = auditLogPort;
    }

    /** 全量，<b>含已停用</b>，带商家数与类目引用数（改之前要知道影响面）。 */
    @GetMapping("/ops/auth-codes")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public List<AuthCodeAdminService.AuthCodeAdminVO> list() {
        return adminService.list();
    }

    /** 新建或更新（按 {@code code} 判定，{@code code} 本身不可改）。 */
    @PostMapping("/ops/auth-codes")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public AuthCodeAdminService.AuthCodeAdminVO save(@RequestBody SaveReq req) {
        var vo = adminService.save(new AuthCodeAdminService.SaveCommand(
                req.code(), req.name(), req.requiredQualification(), req.sort()));
        // 改资质要求 = 放宽或收紧一整类商品的准入，出事要能查到是谁在什么时候动的
        auditLogPort.record("AUTH_CODE_SAVE", vo.code(),
                vo.name() + "｜资质：" + (vo.requiredQualification() == null
                        ? "无" : vo.requiredQualification()));
        return vo;
    }

    /**
     * 启停。<b>停用不撤销存量商家的授权</b>（与行业同一口径）——
     * 它只是不再发放，已经持有的店照常上架。
     */
    @PostMapping("/ops/auth-codes/{code}/enabled")
    @PreAuthorize("@perm.can('" + Perms.CATEGORY_MANAGE + "')")
    public AuthCodeAdminService.AuthCodeAdminVO setEnabled(@PathVariable String code,
                                                           @RequestBody EnabledReq req) {
        var vo = adminService.setEnabled(code, Boolean.TRUE.equals(req.enabled()), req.reason());
        auditLogPort.record("AUTH_CODE_ENABLED", code, req.enabled() + "｜原因：" + req.reason());
        return vo;
    }

    record SaveReq(String code, String name, String requiredQualification, Integer sort) {
    }

    /**
     * @param reason 必填。理由只进审计日志 —— {@code sys_auth_code} 没有 remark 列，
     *               而为它单加一列要手改 {@code schema-test.sql}（那份文件标着「勿手改」
     *               却已经生成不出来了，见 TDD-一期主数据收敛 §8.1）。
     *               审计日志本来就是查「谁在什么时候改了什么」的地方，够用。
     */
    record EnabledReq(Boolean enabled, String reason) {
    }
}
