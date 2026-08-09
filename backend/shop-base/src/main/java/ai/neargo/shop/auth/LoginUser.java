package ai.neargo.shop.auth;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.PermissionCarrier;
import ai.neargo.common.security.rbac.Permissions;

import java.util.List;
import java.util.Set;

/**
 * 统一登录主体（C 端 + 运营端共用）。实现 neargo {@link PermissionCarrier}，
 * 使 {@code @PreAuthorize("@perm.can('码')")} 能直接读其权限码。
 *
 * <p>业务层只经 {@link SecurityUtils} 读它，<b>不直接碰 {@code SecurityContextHolder}</b>。
 *
 * @param realm     凭据池
 * @param userNo    业务用户号（C 端 = userNo，运营端 = staffNo）
 * @param nickname  展示名
 * @param roles     运营端角色码；C 端为空
 * @param perms     运营端权限码；C 端为空（C 端无 RBAC，只有属主鉴权）
 * @param tenantNo  一期恒 MAIN
 * @param dataScope 数据域（运营端按授权；C 端为 SELF）
 */
public record LoginUser(
        Realm realm,
        String userNo,
        String nickname,
        List<String> roles,
        List<String> perms,
        String tenantNo,
        DataScopeSpec dataScope) implements PermissionCarrier {

    public boolean isConsumer() {
        return realm == Realm.CONSUMER;
    }

    @Override
    public Set<String> grantedPermissions() {
        return perms == null ? Set.of() : Set.copyOf(perms);
    }

    /** 通配判定委托 neargo，保证前后端权限码语义一致。 */
    public boolean hasPerm(String code) {
        return Permissions.matches(perms, code);
    }

    /** C 端会话：无角色无权限，数据域恒 SELF（SQL 层防 IDOR 的兜底）。 */
    /**
     * 运营主体。**权限码在登录时算好并放进会话** —— 每次请求回查角色表会让
     * 「改了角色立刻生效」和「每个请求多一次查询」二选一，而前者可以靠重新登录解决。
     */
    public static LoginUser operator(String staffNo, String realName,
                                     java.util.List<String> roles, java.util.List<String> perms) {
        return new LoginUser(Realm.OPERATOR, staffNo, realName, roles, perms, "MAIN",
                DataScopeSpec.ALL);
    }

    public static LoginUser consumer(String userNo, String nickname) {
        return new LoginUser(Realm.CONSUMER, userNo, nickname, List.of(), List.of(), "MAIN",
                DataScopeSpec.of(ScopeDim.SELF, Set.of(userNo)));
    }
}
