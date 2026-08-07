package ai.neargo.shop.auth;

import org.springframework.stereotype.Component;

/**
 * 权限判定门面，供 {@code @PreAuthorize("@perm.can('码')")} 使用。**只服务 /ops**。
 *
 * <p>C 端没有 RBAC（只有属主鉴权），B 端靠数据域作用域 —— 都不该调用这里。
 * 判定只看会话里的权限码：权限码是登录时算好的快照，运营改了角色要重新登录才生效
 * （或由 permStamp 强制失效，见 TokenStore.SessionData）。
 */
@Component("perm")
public class PermChecker {

    /** 超管通配：一个码顶所有。生产要谨慎发放。 */
    private static final String WILDCARD = "*";

    public boolean can(String code) {
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null || user.realm() != Realm.OPERATOR) {
            // 非运营会话一律拒绝：C 端 token 不该因为「碰巧没有这个权限码」而落到这里
            return false;
        }
        var perms = user.perms();
        if (perms == null || perms.isEmpty()) {
            return false;
        }
        return perms.contains(WILDCARD) || perms.contains(code);
    }
}
