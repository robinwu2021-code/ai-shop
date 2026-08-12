package ai.neargo.shop.platform.perm.api;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.perm.PermConfigService;
import ai.neargo.shop.platform.perm.PermConfigService.MenuFunctionVO;
import ai.neargo.shop.platform.perm.PermConfigService.RoleVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 权限配置与动态菜单。
 *
 * <p>{@code /ops/menu} <b>不挂权限码</b>：每个登录的人都要拿自己的菜单，
 * 给它配权限等于「没有菜单权限的人看不到任何菜单」——
 * 与 {@code /ops/auth/me} 同一类。<b>它返回的内容本身已经按人裁过</b>。
 *
 * <p>配置读取（功能矩阵 / 角色）挂 {@code staff:manage}：能配角色的人才需要看它。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPermConfigController {

    private final PermConfigService permConfigService;

    public OpsPermConfigController(PermConfigService permConfigService) {
        this.permConfigService = permConfigService;
    }

    /**
     * 当前登录人的动态菜单。
     *
     * <p>返回里带 {@code backendStatus} —— 端上据此把未实现的项
     * <b>灰显 + 「待建」角标、不可点</b>，而不是藏起来：
     * 藏起来运营不知道平台规划了这个功能，可点则是死按钮。
     */
    @GetMapping("/ops/menu")
    public List<MenuFunctionVO> menu() {
        return permConfigService.menu();
    }

    /** 功能矩阵全量（配置页用），含每个功能点的后端实现状态。 */
    @GetMapping("/ops/perm/functions")
    @PreAuthorize("@perm.can('" + Perms.STAFF_MANAGE + "')")
    public List<MenuFunctionVO> functions(@RequestParam(required = false) String end) {
        return permConfigService.functions(end);
    }

    @GetMapping("/ops/perm/roles")
    @PreAuthorize("@perm.can('" + Perms.STAFF_MANAGE + "')")
    public List<RoleVO> roles(@RequestParam(required = false) String end) {
        return permConfigService.roles(end);
    }

    @GetMapping("/ops/perm/roles/{roleCode}/points")
    @PreAuthorize("@perm.can('" + Perms.STAFF_MANAGE + "')")
    public List<String> rolePoints(@PathVariable String roleCode) {
        return permConfigService.rolePoints(roleCode);
    }
}
