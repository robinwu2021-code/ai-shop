package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.gateway.core.security.AbstractAccessDecider;

/** 运营端：问现有的权限检查器 —— 与方法注解里的 {@code @perm.can} 是同一个 bean、同一份现算的权限码。 */
public class OpsAccessDecider extends AbstractAccessDecider {

    private final PermChecker perm;

    public OpsAccessDecider(PermChecker perm) {
        this.perm = perm;
    }

    @Override
    protected boolean has(String code) {
        return perm.can(code);
    }
}
