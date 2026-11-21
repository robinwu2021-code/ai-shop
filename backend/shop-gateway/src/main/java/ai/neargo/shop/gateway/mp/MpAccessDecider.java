package ai.neargo.shop.gateway.mp;

import ai.neargo.shop.gateway.core.security.AbstractAccessDecider;

/**
 * C 端：网关不判权限（TDD-网关层与统一权限 §2.25）。
 *
 * <p>C 端没有角色与权限码，要不要登录由业务代码逐个入口决定，所以这里对谁都放行。
 * 它存在的意义是让 C 端也走网关的「定位」这一步：入口进索引、入库，运营可以在「接口资源」页把某一条停用。
 */
public class MpAccessDecider extends AbstractAccessDecider {

    @Override
    protected boolean has(String code) {
        return false;
    }
}
