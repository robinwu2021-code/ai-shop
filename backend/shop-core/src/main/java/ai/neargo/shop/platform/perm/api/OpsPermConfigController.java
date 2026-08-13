package ai.neargo.shop.platform.perm.api;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.perm.PermConfigService;
import ai.neargo.shop.platform.perm.PermConfigService.MenuFunctionVO;
import ai.neargo.shop.platform.perm.PermConfigService.RoleVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import ai.neargo.shop.auth.SecurityUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_READ + "')")
    public List<MenuFunctionVO> functions(@RequestParam(required = false) String end) {
        return permConfigService.functions(end);
    }

    @GetMapping("/ops/perm/roles")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_READ + "')")
    public List<RoleVO> roles(@RequestParam(required = false) String end) {
        return permConfigService.roles(end);
    }

    @GetMapping("/ops/perm/roles/{roleCode}/points")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_READ + "')")
    public List<String> rolePoints(@PathVariable String roleCode) {
        return permConfigService.rolePoints(roleCode);
    }

    // ---------------------------------------------------------------- 写侧

    /**
     * 新建自定义角色。
     *
     * <p>判权已改成读库，所以新建角色 + 勾功能点是**真的生效**的 ——
     * 在换源之前它只能改菜单，而「菜单能看、接口 403」是最坏的一种。
     */
    @PostMapping("/ops/perm/roles")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public RoleVO createRole(@RequestBody CreateRoleReq req) {
        return permConfigService.createRole(req.roleCode(), req.name(),
                SecurityUtils.currentUserNo());
    }

    // ---------------------------------------------------------------- 菜单排序

    /**
     * 菜单分区上移 / 下移。
     *
     * <p>挂 {@code IAM_ROLE_GRANT}：能配权限的人才该动菜单结构。
     * 排序影响**所有人**看到的菜单，不是个人偏好。
     */
    @PostMapping("/ops/perm/functions/{functionCode}/move")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public void moveFunction(@PathVariable String functionCode, @RequestBody MoveReq req) {
        permConfigService.moveFunction(functionCode, req.direction(), SecurityUtils.currentUserNo());
    }

    /** 功能点（菜单叶子 / 页面 tab）上移 / 下移。 */
    @PostMapping("/ops/perm/points/{pointCode}/move")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public void movePoint(@PathVariable String pointCode, @RequestBody MoveReq req) {
        permConfigService.movePoint(pointCode, req.direction(), SecurityUtils.currentUserNo());
    }

    /**
     * 整段重排（拖动用）。传该父级下的**完整顺序**。
     *
     * <p>与 {@code /move} 并存而不是取代它：↑/↓ 是键盘可达的那条路，
     * 而原生拖拽在触屏与辅助技术下不可靠。
     */
    @PostMapping("/ops/perm/functions/reorder")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public void reorderFunctions(@RequestBody ReorderReq req) {
        permConfigService.reorderFunctions(req.codes(), SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/perm/points/reorder")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public void reorderPoints(@RequestBody ReorderPointsReq req) {
        permConfigService.reorderPoints(req.functionCode(), req.codes(), SecurityUtils.currentUserNo());
    }

    public record ReorderReq(List<String> codes) {
    }

    public record ReorderPointsReq(String functionCode, List<String> codes) {
    }

    public record MoveReq(PermConfigService.MoveDirection direction) {
    }

    /** 设置角色的功能点（整体替换）。**预置角色拒绝修改**。 */
    @PostMapping("/ops/perm/roles/{roleCode}/points")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public RoleVO setRolePoints(@PathVariable String roleCode, @RequestBody PointsReq req) {
        return permConfigService.setRolePoints(roleCode, req.pointCodes(),
                SecurityUtils.currentUserNo());
    }

    /**
     * <b>强制该角色的成员重新登录</b>（紧急撤回）。
     *
     * <p>普通调权不需要它 —— 改完功能点本来就立刻生效（判权现算）。
     * 这条是给「权限开错了要立刻收回」准备的：跨实例最坏要等一个 TTL（60 秒），
     * 那一刻 60 秒不能接受。
     *
     * <p>用 {@code iam:role:grant}：能改这个角色权限的人，才有资格把它的成员踢下线。
     */
    @PostMapping("/ops/perm/roles/{roleCode}/force-logout")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public ForceLogoutVO forceLogout(@PathVariable String roleCode) {
        return new ForceLogoutVO(
                permConfigService.forceLogoutRole(roleCode, SecurityUtils.currentUserNo()));
    }

    /** @param kicked 实际踢掉的会话数 —— 一个人可能有多个设备/标签页 */
    public record ForceLogoutVO(int kicked) {
    }

    /** 删除自定义角色。预置角色、以及**还有人在用的**都拒绝。 */
    /** 改角色展示名。**只改名不改码** —— 码是授权的键，改了等于换一个角色。 */
    @PostMapping("/ops/perm/roles/{roleCode}/rename")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public PermConfigService.RoleVO renameRole(@PathVariable String roleCode,
                                               @RequestBody RenameReq req) {
        return permConfigService.renameRole(roleCode, req.name(), SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/perm/roles/{roleCode}/delete")
    @PreAuthorize("@perm.can('" + Perms.IAM_ROLE_GRANT + "')")
    public void deleteRole(@PathVariable String roleCode) {
        permConfigService.deleteRole(roleCode, SecurityUtils.currentUserNo());
    }

    public record RenameReq(String name) {
    }

    public record CreateRoleReq(String roleCode, String name) {
    }

    public record PointsReq(List<String> pointCodes) {
    }
}
