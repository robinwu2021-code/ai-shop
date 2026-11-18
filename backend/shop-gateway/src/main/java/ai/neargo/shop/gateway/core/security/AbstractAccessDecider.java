package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;

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
        if (resource.access() == Access.POINT && !resource.rule().test(this::has)) {
            return Decision.forbidden(resource.rule().codes().stream().filter(code -> !has(code)).toList());
        }
        return extra(user, resource);
    }

    /** 当前登录人有没有这个权限码。 */
    protected abstract boolean has(String code);

    /** 权限式之外的条件（运营开关、方案是否包含等）。默认没有。 */
    protected Decision extra(LoginUser user, ApiResource resource) {
        return Decision.permit();
    }
}
