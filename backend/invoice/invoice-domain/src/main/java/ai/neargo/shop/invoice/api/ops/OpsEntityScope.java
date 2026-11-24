package ai.neargo.shop.invoice.api.ops;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import java.util.HashSet;
import java.util.Set;

/**
 * 运营会话能看哪些主体（TDD-发票模块 §18.7）。发票模块可选打包、不装数据域引擎，所以照物流运单
 * （{@code OpsShipmentController#entityScope}）显式算：
 *
 * <ul>
 *   <li>{@code null} = 不限（超管、未配数据域）</li>
 *   <li>商家维度的主体号集合 = 只看这些</li>
 *   <li>空集 = 一张都看不到（只配了社区 / 自提点维度：发票表上没有这两列，与引擎「缺锚点」同口径）</li>
 * </ul>
 */
final class OpsEntityScope {

    private OpsEntityScope() {
    }

    static Set<String> current() {
        return of(SecurityUtils.currentUser().map(LoginUser::dataScope).orElse(null));
    }

    static Set<String> of(DataScopeSpec spec) {
        if (spec == null || spec.all()) return null;
        var out = new HashSet<String>();
        for (DataScopeSpec.Rule r : spec.rules())
            if (ScopeDim.MERCHANT.equals(r.dim()) && r.refs() != null) out.addAll(r.refs());
        return Set.copyOf(out);
    }

    /** 范围外的主体按「不存在」处理，不泄露它存在与否。 */
    static boolean allows(Set<String> scope, String entityNo) {
        return scope == null || scope.contains(entityNo);
    }
}
