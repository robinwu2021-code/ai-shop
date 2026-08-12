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
    /**
     * 不受数据域限制的运营（超管、商品运营等全量角色）。
     *
     * <p>此前**所有**运营都走这一条，`DataScopeSpec.ALL` 写死在这里 ——
     * 于是 `sys_ops_staff` 上配好的数据域一路带到 token，
     * 而拦截器拿到的永远是「全量」。基础设施是通的，缺的就是这一句。
     */
    public static LoginUser operator(String staffNo, String realName,
                                     java.util.List<String> roles, java.util.List<String> perms) {
        return operator(staffNo, realName, roles, perms, DataScopeSpec.ALL);
    }

    /**
     * 带数据域的运营。
     *
     * <p><b>空 = 不限定</b>：三个归属键都没配时传 {@link DataScopeSpec#ALL}，
     * 与「配了一个空值」要长得一样 —— 否则「不限定」有两种表示，
     * 而其中一种会被当成「限定到空字符串」，结果是这个人什么都看不到。
     *
     * <p>数据域在**签发那一刻**固化进会话：改了数据域要重建会话才生效，
     * 所以 {@code setStaffScope} 会踢掉在线会话。
     */
    public static LoginUser operator(String staffNo, String realName,
                                     java.util.List<String> roles, java.util.List<String> perms,
                                     DataScopeSpec scope) {
        return new LoginUser(Realm.OPERATOR, staffNo, realName, roles, perms, "MAIN",
                scope == null ? DataScopeSpec.ALL : scope);
    }

    public static LoginUser consumer(String userNo, String nickname) {
        return new LoginUser(Realm.CONSUMER, userNo, nickname, List.of(), List.of(), "MAIN",
                DataScopeSpec.of(ScopeDim.SELF, Set.of(userNo)));
    }
}
