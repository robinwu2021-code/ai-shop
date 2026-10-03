package ai.neargo.shop.platform;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把运营员工身上的三个归属键翻成数据域规则。**纯函数，无状态。**
 *
 * <p>从 {@code OpsServiceImpl} 抽出来（行为一字未改），因为它现在有第二个调用方：
 * 会话外置之后，数据域不再在签发那一刻固化进会话，而是**每个请求由
 * {@code OperatorIdentityLoader} 现算** —— 两处必须用同一份逻辑，
 * 否则「登录时算出来的域」与「请求时算出来的域」会悄悄分岔，
 * 而分岔的表现是「他昨天还看得见的数据今天看不见了」。
 */
public final class StaffScopes {

    private StaffScopes() {
    }

    /**
     * @param perms 已解析出的权限码；含 {@code *} 表示全量角色
     *
     * <p><b>全量角色一律 ALL</b>：超管即使库里有残留的归属键也不受限 ——
     * 与 {@code setStaffScope} 拒绝给全量角色配数据域是同一条规矩的两面。
     *
     * <p>三个键都为空时返回 {@link DataScopeSpec#ALL}。**空 = 不限定**，
     * 不能返回一条 refs 为空的规则 —— 那会被翻成 {@code IN ()}，这个人什么都看不到。
     */
    public static DataScopeSpec of(String merchantNo, String communityNo, String pickupNo,
                                   List<String> perms) {
        if (perms != null && perms.contains("*")) {
            return DataScopeSpec.ALL;
        }
        List<DataScopeSpec.Rule> rules = new ArrayList<>();
        addRule(rules, ScopeDim.MERCHANT, merchantNo);
        addRule(rules, ScopeDim.COMMUNITY, communityNo);
        addRule(rules, ScopeDim.PICKUP, pickupNo);
        return rules.isEmpty() ? DataScopeSpec.ALL : new DataScopeSpec(false, rules);
    }

    private static void addRule(List<DataScopeSpec.Rule> rules, String dim, String value) {
        if (value != null && !value.isBlank()) {
            rules.add(new DataScopeSpec.Rule(dim, Set.of(value)));
        }
    }
}
