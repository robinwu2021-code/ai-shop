package ai.neargo.shop.elec.api.ops;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/**
 * 运营端接口的权限判定。
 *
 * <p>权限码由<b>主系统按它自己的规则判完</b>（角色现查、认模块通配），认令牌时只把元器件用得到的那几个码（{@code ElecInternal.OPS_PERMS}）的结果带过来
 * （见 {@code InternalElecEndpoint}）。这里只看结果，不重新实现一遍 RBAC ——
 * 两处各算一遍的话，迟早会出现「运营端显示了入口、元器件回 403」。
 */
public final class ElecOpsGuard {

    private ElecOpsGuard() {
    }

    /** @return 运营号（staff_no） */
    public static String require(String perm) {
        LoginUser u = SecurityUtils.requireUser();
        if (u.realm() != Realm.OPERATOR) {
            throw new ai.neargo.shop.common.GlobalExceptionHandler.UnauthorizedException();
        }
        if (u.perms() == null || !u.perms().contains(perm)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return u.userNo();
    }
}
