package ai.neargo.shop.gateway.core.function;

import ai.neargo.shop.auth.Realm;

/**
 * 一个功能点（TDD-网关层与统一权限 §2.6）。
 *
 * @param code        现有权限码，原样沿用，如 {@code biz:stock}
 * @param realm       属于哪一端：商家或运营
 * @param type        菜单或动作
 * @param parent      菜单树上的父节点，可空
 * @param entitlement 方案权益码，可空
 * @param downstream  下游映射，可空
 */
public record FunctionPoint(String code, Realm realm, PointType type, String parent, String entitlement,
                            Downstream downstream) {

    /** 只有权限码的动作点：今天全部功能点都是这一种。 */
    public static FunctionPoint action(String code, Realm realm) {
        return new FunctionPoint(code, realm, PointType.ACTION, null, null, null);
    }
}
