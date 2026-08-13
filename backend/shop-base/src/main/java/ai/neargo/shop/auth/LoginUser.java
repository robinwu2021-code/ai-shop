package ai.neargo.shop.auth;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.PermissionCarrier;

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

    /**
     * 换上<b>此刻</b>的角色与数据域（运营端每请求现算，见 {@code OperatorTokenAuthFilter}）。
     *
     * <p>会话里那份是登录那一刻的快照，只用作解析失败时的回落。
     * 判权（{@code @perm.can}）读的就是这个对象的 {@code roles()} ——
     * 不换的话会出现「菜单按新角色画、判权按旧角色算」，而两边各自都说得通。
     */
    public LoginUser withRolesAndScope(List<String> newRoles, DataScopeSpec newScope) {
        return new LoginUser(realm, userNo, nickname, newRoles, perms, tenantNo, newScope);
    }

    @Override
    public Set<String> grantedPermissions() {
        return perms == null ? Set.of() : Set.copyOf(perms);
    }

    /** C 端会话：无角色无权限，数据域恒 SELF（SQL 层防 IDOR 的兜底）。 */
    /**
     * 运营主体。
     *
     * <p><b>这里塞进去的角色/权限/数据域只是回落用的快照</b>：判权走
     * {@code LivePermResolver}、身份走 {@code LiveIdentityResolver}，
     * 两者都是每请求现算（各自带 TTL 快照，代价是一次 map 查找）。
     * 会话里留一份，只为了「解析器没装上/库抖」时不至于全员失权。
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
