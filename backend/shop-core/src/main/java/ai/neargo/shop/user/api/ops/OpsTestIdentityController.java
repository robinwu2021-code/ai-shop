package ai.neargo.shop.user.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.user.service.TestIdentityService;
import ai.neargo.shop.user.service.TestIdentityService.TestIdentityVO;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端 · 测试身份白名单（TDD-测试身份白名单登录）：手机号或邮箱加进来，就能用服务器配置里的
 * 统一验证码与统一密码登录 / 注册。统一码与密码不经过这里、也不在页面上显示。
 *
 * <p>权限沿用测试号白名单那两个码（{@code SYSTEM_TESTPHONE_READ / UPDATE}）：同一类东西、同一页，
 * 两个码都只有超管通配能到。护栏与审计在 {@code TestIdentityServiceImpl}。
 */
@Profile("ops")
@RestController
public class OpsTestIdentityController {

    private final TestIdentityService service;

    public OpsTestIdentityController(TestIdentityService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_READ + "')")
    @GetMapping("/ops/test-identities")
    public TestIdentityList list() {
        return new TestIdentityList(service.enabled(), service.list());
    }

    /** 加一条或改备注（按 类型 + 规范化后的值 认） */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_UPDATE + "')")
    @PostMapping("/ops/test-identities")
    public TestIdentityList save(@RequestBody TestIdentityReq req) {
        return new TestIdentityList(service.enabled(), service.save(req.identityType(), req.identityValue(), req.remark()));
    }

    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_UPDATE + "')")
    @PostMapping("/ops/test-identities/{id}/enabled")
    public TestIdentityList setEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        return new TestIdentityList(service.enabled(), service.setEnabled(id, Boolean.TRUE.equals(req.enabled())));
    }

    /* 删走 POST，理由同 OpsTestPhoneController；注释不能夹在 @PreAuthorize 与 mapping 之间 */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_UPDATE + "')")
    @PostMapping("/ops/test-identities/{id}/remove")
    public TestIdentityList remove(@PathVariable Long id) {
        return new TestIdentityList(service.enabled(), service.remove(id));
    }

    /**
     * @param switchOn 服务器总开关 {@code shop.auth.test-identity.enabled}；关着时名单存在也不生效
     * @param items    名单
     */
    public record TestIdentityList(boolean switchOn, List<TestIdentityVO> items) {
    }

    /**
     * @param identityType  PHONE / EMAIL
     * @param identityValue 手机号（带国码，如 +886900000102）或邮箱
     * @param remark        为什么加这一条
     */
    public record TestIdentityReq(String identityType, String identityValue, String remark) {
    }

    /** @param enabled true = 启用 */
    public record EnabledReq(Boolean enabled) {
    }
}
