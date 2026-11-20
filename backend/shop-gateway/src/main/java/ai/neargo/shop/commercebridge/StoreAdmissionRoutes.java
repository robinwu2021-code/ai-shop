package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.starter.security.CommerceRoutePolicy;

/**
 * 把「门店准入」那张路由表读出来给人看（TDD-权限统一到网关层-修复方案 第 1 步）。
 *
 * <p>B 端的一个请求在网关按角色判权之前，先过门店准入：这条路由对这家店开不开。
 * 那张表写在 {@link CommerceRoutePolicy} 的代码里，此前没有任何地方能把它和网关的资源索引放在一起看。
 * 这里只读、不判：给一条入口的方法与地址模式，说出它落在准入表的哪一类。真正的准入仍由
 * {@link ShopCommerceEndContextAdapter} 在请求上执行，两处用的是同一张表。
 */
public final class StoreAdmissionRoutes {

    private static final CommerceRoutePolicy POLICY = new CommerceRoutePolicy(ShopStoreMemberRoutes.ALL);
    /** 地址模式里的路径变量换成这个值再去匹配；它满足准入表对编号的格式要求 */
    private static final String SAMPLE_ID = "ID0000000000000001";

    /** 与 {@code BizContextFilter} 里先放行的那几个地址相同 */
    private static final java.util.Set<String> PRE_LOGIN = java.util.Set.of(
            "/biz/auth/login", "/biz/auth/staff-login", "/biz/auth/otp/send", "/biz/auth/email-otp/send",
            "/biz/auth/logout", "/biz/auth/identities", "/biz/auth/identities/enter",
            "/biz/auth/captcha/challenge", "/biz/auth/captcha/verify");

    private StoreAdmissionRoutes() {
    }

    /**
     * @return 如 {@code ACCOUNT}、{@code BOOTSTRAP}、{@code END_PROTECTED · END_BIZ_APP · biz:receive}、{@code DENY}；
     *         不是 B 端入口返回 null
     */
    public static String describe(String method, String pattern) {
        if (pattern == null || !pattern.startsWith("/biz/")) {
            return null;
        }
        // 这两类根本不经过门店准入，照着准入表读会被误标成 DENY（2026-11-19 用测试账号实测对出来的）：
        // 支付进件走的是支付自己的那条认证链；登录前的几个动作在过滤器里先放行了
        if (pattern.startsWith("/biz/pay/v2/")) {
            return "NOT_APPLIED · PAY_ONBOARDING_CHAIN";
        }
        if (PRE_LOGIN.contains(pattern)) {
            return "NOT_APPLIED · PRE_LOGIN";
        }
        String path = pattern.replaceAll("\\{[^/}]+}", SAMPLE_ID).replace("/**", "/x").replace("*", "x");
        CommerceRoutePolicy.Route route = POLICY.match("*".equals(method) ? "GET" : method, path);
        StringBuilder out = new StringBuilder(route.lane().name());
        if (route.entitlement() != null) {
            out.append(" · ").append(route.entitlement());
        }
        if (route.capability() != null) {
            out.append(" · ").append(route.capability());
        }
        if (route.newModelOnly()) {
            out.append(" · NEW_MODEL_ONLY");
        }
        return out.toString();
    }
}
