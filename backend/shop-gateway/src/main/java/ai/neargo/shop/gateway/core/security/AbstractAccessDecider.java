package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;

/**
 * 判定的固定流程（TDD-网关层与统一权限 §2.21）：公开 → 只需登录 / 留给方法 → 权限式 → 附加条件。
 * 子类只回答「此人有没有这个权限码」；判定顺序不会被子类改乱。
 */
public abstract class AbstractAccessDecider implements AccessDecider {

    @Override
    public final Decision decide(LoginUser user, ApiResource resource) {
        if (resource.access() == Access.PUBLIC) {
            return Decision.permit();
        }
        if (resource.access() == Access.POINT && !resource.rule().test(this::has, node -> hasScoped(resource, node))) {
            return Decision.forbidden(resource.rule().leaves().stream()
                    .filter(leaf -> leaf.kind() == PermRule.Kind.SCOPED ? !hasScoped(resource, leaf) : !has(leaf.code()))
                    .map(PermRule::code).toList());
        }
        return extra(user, resource);
    }

    /** 当前登录人有没有这个权限码。 */
    protected abstract boolean has(String code);

    /**
     * 带范围的权限码（在某个管理空间里对某类资源持有）成不成立。默认不成立：
     * 不认识这种写法的端遇到它就拒绝，而不是放行。
     */
    protected boolean hasScoped(ApiResource resource, PermRule node) {
        return false;
    }

    /** 权限式之外的条件（运营开关、方案是否包含等）。默认没有。 */
    protected Decision extra(LoginUser user, ApiResource resource) {
        return Decision.permit();
    }
}
