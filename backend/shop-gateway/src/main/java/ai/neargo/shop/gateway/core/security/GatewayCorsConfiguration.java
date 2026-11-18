package ai.neargo.shop.gateway.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** 三端共用的 CORS 来源（自 SecurityConfig 原样搬来；bean 名仍是 {@code corsSource}，别处按名注入）。 */
public class GatewayCorsConfiguration {

    /**
     * 生产站点的来源。<b>浏览器发的每一个 POST/fetch 都带 Origin，同源也带</b> ——
     * 所以「运营端和后端同域，不用配 CORS」是个误解，而它的代价是全线 403。
     *
     * <h2>2026-09-01：线上运营端从来就没能登录过</h2>
     * 这个列表里此前只有 localhost 的开发端口，一条生产域名都没有 ——
     * 而上面那句注释写着「本地开发的三个端口 <b>+ 生产同源</b>」，
     * <b>注释里的那一半从来不存在</b>。
     *
     * <p>症状是「登录提示无权限」：nginx 日志里 {@code POST /ops/auth/login → 403}，
     * 响应体 {@code Invalid CORS request}，请求<b>在到达登录逻辑之前就被拦了</b>，
     * 所以 {@code ops_login_log} 里一条记录都没有 —— 查判权、查角色、查权限点全是好的。
     *
     * <p>而后端直连（{@code curl localhost:8081}）一直是 200：
     * <b>curl 不带 Origin，也不受同源策略约束。</b>
     * 这件事只有真的用浏览器打才会暴露，与本类下面那条
     * 「E2E-2 是 Node 脚本，一路绿灯」是同一个盲区。
     *
     * <p>写进<b>默认值</b>而不是只留配置项：默认值缺了的表现是静默 403，
     * 与「配置项漏配」是同一类失效 —— 这个仓库只服务这一个站点，
     * 域名是已知固定的（nginx 配置里也写死了），没有理由让它可漏。
     */
    private static final java.util.List<String> PROD_ORIGINS = java.util.List.of(
            "https://www.hxmall.top", "https://hxmall.top");

    /**
     * 本地开发的来源。
     *
     * <p>为什么必须有它：三个前端都是独立起的（uni-app dev / next dev），
     * 与后端天然跨源。此前后端**一条 CORS 头都没有** —— 预检因为 Spring Security
     * 放行 OPTIONS 而返回 200，真实请求却被浏览器拦掉，前端看到的只是「网络异常」。
     *
     * <p>而这件事**只有真的用浏览器打才会暴露**：E2E-2 是 Node 脚本，
     * 不受同源策略约束，一路绿灯。
     *
     * <p>不写 {@code *}：带 Authorization 头的请求在 {@code allowCredentials} 下
     * 本来就不允许通配，而且通配等于把内网运营端的接口对任意站点开放。
     */
    private static final java.util.List<String> ALLOWED_ORIGINS = java.util.List.of(
            "http://localhost:3100", "http://127.0.0.1:3100",   // ops-web
            // 第二个 ops-web 开发端口：**并行开发时两个会话各起一份**，
            // 共用 3100 的结果是后起的那个静默失败（端口被占，页面连的还是别人的后端）
            "http://localhost:3101", "http://127.0.0.1:3101",
            /*
             * c-app / b-app dev。**列到 5177**，理由与上面 3101 那条相同：
             * vite 端口被占就自动往后顺延，而并行会话同时开着两三个 dev server
             * 是常态 —— 顺延到白名单之外的那一刻，页面看到的只是「网络异常」，
             * 没有任何一处会说是 CORS。
             */
            "http://localhost:5173", "http://127.0.0.1:5173",
            "http://localhost:5174", "http://127.0.0.1:5174",
            "http://localhost:5175", "http://127.0.0.1:5175",
            "http://localhost:5176", "http://127.0.0.1:5176",
            "http://localhost:5177", "http://127.0.0.1:5177");

    /** 额外来源，逗号分隔。换域名或加二级域名时不必改代码 */
    @Value("${shop.cors.extra-origins:}")
    private String extraOrigins = "";

    /*
     * **返回类型必须写成 UrlBasedCorsConfigurationSource，不能写成接口。**
     *
     * Spring Security 在造每一个 HttpSecurity 时，会按「容器里有没有 UrlBasedCorsConfigurationSource 类型的 bean」
     * 决定要不要默认加 CorsFilter。bean 还没实例化时，Spring 只能从工厂方法的返回类型判断它的类型 ——
     * 返回类型写成接口，这条判断就取决于「本 bean 是否恰好已经先被创建」。
     * 拆分前它和所有链在同一个配置类里、排在最前，于是支付接入的四条链一直带着 CorsFilter；
     * 拆到别的类之后创建顺序变了，那四条链的 CorsFilter 就没了（M2 的认证链快照当场抓到）。
     * 写成具体类型后，这件事不再依赖创建顺序。
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsSource() {
        var cfg = new CorsConfiguration();
        /*
         * 开发端口 + 生产域名 + 额外配置的（`shop.cors.extra-origins`，逗号分隔）。
         * 第三项留给将来换域名或加二级域名时**不必改代码**，
         * 但生产域名本身在上面写死了 —— 靠配置的那一半会被漏掉，这次就是。
         */
        var origins = new java.util.ArrayList<>(ALLOWED_ORIGINS);
        origins.addAll(PROD_ORIGINS);
        for (String extra : extraOrigins.split(",")) {
            if (!extra.isBlank()) {
                origins.add(extra.trim());
            }
        }
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        /*
         * 请求头放开为 `*`。
         *
         * 本来只列了业务要用的四个（Authorization / X-Store-No / Accept-Language /
         * Idempotency-Key），但真的用浏览器打的时候被拦了 —— 拦在一个
         * **源码里根本搜不到**的 `x-user-id` 上：那是浏览器扩展注入的。
         * 白名单挡不住这类头，而它们对后端无害（后端只读自己认识的那几个）。
         *
         * 响应头没有跟着放开：允许对方**发**什么，和允许它**读**什么，是两件事。
         */
        cfg.setAllowedHeaders(java.util.List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
