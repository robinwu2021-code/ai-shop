package ai.neargo.shop.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import ai.neargo.shop.auth.ApiAuthEntryPoint;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.OperatorTokenAuthFilter;
import ai.neargo.shop.auth.TokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;

/**
 * 三条过滤器链 —— **越权防线的第 ① 道**（TDD-backend §5.2）。
 *
 * <p>链按前缀分开的收益：拿 C 端 token 打 {@code /ops/**} 在进业务代码之前就 401，
 * 不依赖任何 Controller 记得判身份。若合成一条链靠参数区分，这个保证就没了。
 *
 * <p>无状态：不建 HttpSession（{@code STATELESS}），会话在 {@link TokenStore} 里。
 * 小程序端没有 cookie 语义，靠 session 会在 App/H5/小程序三端表现不一致。
 */
@Configuration
@EnableMethodSecurity   // 开 @PreAuthorize("@perm.can('码')")，仅 /ops 使用
/*
 * ⚠️ **只在 Web 应用里装**。
 *
 * worker profile 把 web-application-type 设成 none（批量任务与 API 抢线程池时，
 * 结算跑一轮能把下单拖到超时）。没有这个条件的话，Spring 仍会去建 consumerChain，
 * 而 HttpSecurity 在非 Web 上下文里根本不存在 —— **worker 直接起不来**，
 * 报的是「No qualifying bean of type HttpSecurity」，一个字都不提 profile。
 *
 * 这个缺陷躺了很久没被发现，因为 **worker 从来没被部署过**：
 * 生产跑 api,ops，而 14 个定时任务全挂在 worker 上。
 * 「默认关着的那一半没人测」——而关着的那一半恰恰是要启用的那一半。
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

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

    @Bean
    org.springframework.web.cors.CorsConfigurationSource corsSource() {
        var cfg = new org.springframework.web.cors.CorsConfiguration();
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
        var src = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    /**
     * C 端与 B 端：登录可选（游客可逛），属主鉴权在 Service 层。
     *
     * <p>{@code @Profile("api")}（S8）：ops 部署不装这条链。但真正的隔离不靠它——
     * {@code /mp/**} 的 Controller 自己也带 {@code @Profile("api")}，
     * 在 ops 部署里**路由压根不存在**，打过去是 404 而不是 401。
     * 少一条链只是少一层过滤器；少一个路由才是少一个攻击面。
     */
    @Bean
    @Profile("api")
    @Order(1)
    SecurityFilterChain merchantChain(HttpSecurity http, TokenStore tokenStore,
                                      ObjectProvider<BizIdentityResolver> resolver) throws Exception {
        return http
                .securityMatcher("/biz/**")
                .cors(c -> c.configurationSource(corsSource()))
                .csrf(csrf -> csrf.disable())          // 无 cookie 会话，CSRF 不适用
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        /*
                         * 登录前的三个动作都必须放行。**发验证码也在其中** ——
                         * 它此前漏在白名单外，于是商家点「获取验证码」拿到 401：
                         * 要先登录才能拿到登录用的验证码，谁也进不来。
                         * 而这条路径只有真的从登录页点一次才会走到。
                         *
                         * staff-login 是**员工独立登录**：他可能根本没有 C 端账号，
                         * 要求先登录才能登录是个死循环。
                         */
                        .requestMatchers("/biz/auth/login", "/biz/auth/staff-login",
                                "/biz/auth/otp/send").permitAll()
                        .anyRequest().authenticated())
                /*
                 * **只认 btk_（A7）。** 此前这条链与 /mp/** 合用一条，挂的是
                 * ConsumerTokenAuthFilter —— 商家拿的是 C 端的 ctk_，于是
                 * LoginUser.userNo 这一个字段里 C 端塞 usr_account.user_no、
                 * B 端塞 mch_account.mch_account_no，靠号段恰好不撞。
                 * 分链之后跨端令牌在第一道就被拒，不是靠约定。
                 */
                .addFilterBefore(new MerchantTokenAuthFilter(tokenStore),
                        UsernamePasswordAuthenticationFilter.class)
                // BizContextFilter 必须在认证之后：它要用登录态去解析经营侧作用域
                .addFilterAfter(new BizContextFilter(resolver.getIfAvailable(() -> BizIdentityResolver.NONE)),
                        MerchantTokenAuthFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new ApiAuthEntryPoint()))
                .build();
    }

    /**
     * C 端（{@code /mp/**}）：只认 {@code ctk_}。
     *
     * <p><b>整条链放行到业务层</b>：游客能逛商品、看门店主页，需要登录的端点
     * 自己调 {@code SecurityUtils.currentUserNo()} 抛 401。
     * 这是刻意的 —— 门店主页未登录不该 401，所以才有分开的
     * {@code currentUserNoOrNull()}。哪些端点要登录由 {@code MpEndpointAuthTest} 逐条钉住。
     */
    @Bean
    @Profile("api")
    @Order(2)
    SecurityFilterChain consumerChain(HttpSecurity http, TokenStore tokenStore) throws Exception {
        return http
                .securityMatcher("/mp/**")
                .cors(c -> c.configurationSource(corsSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .addFilterBefore(new ConsumerTokenAuthFilter(tokenStore),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new ApiAuthEntryPoint()))
                .build();
    }

    /** 运营端：全部需要登录 + RBAC。仅 ops 部署装配，且 ops 只在内网可达。 */
    @Bean
    @Profile("ops")
    @Order(3)
    SecurityFilterChain operatorChain(
            HttpSecurity http, TokenStore tokenStore,
            ObjectProvider<ai.neargo.shop.auth.LiveIdentityResolver> identityResolver) throws Exception {
        return http
                .securityMatcher("/ops/**")
                .cors(c -> c.configurationSource(corsSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // 忘记密码的两条同样免鉴权：忘了密码自然登不进来。
                        // 安全性靠邮件里那个一次性令牌，以及「账号不存在也返回成功」
                        .requestMatchers("/ops/auth/login", "/ops/auth/forgot",
                                "/ops/auth/reset").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new OperatorTokenAuthFilter(tokenStore, identityResolver),
                        UsernamePasswordAuthenticationFilter.class)
                // 401 要带包体，且区分「没登录」与「登录过期」—— 见 ApiAuthEntryPoint
                .exceptionHandling(e -> e.authenticationEntryPoint(new ApiAuthEntryPoint()))
                .build();
    }

    /** 公共与回调：{@code /callback/**} 各自验签，不走 Bearer（[API 清单 §5.2]）。 */
    @Bean
    @Profile({"api", "ops"})
    @Order(4)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        return http
                // /uploads/** 是商品图：**游客必须能看**，否则未登录逛首页全是裂图。
                // 图本身不是秘密（它就是要给买家看的），访问控制在"能不能传"那一侧。
                //
                // ⚠️ 这句话**只对商品图成立**。证件与售后凭证曾经也落在这个目录下，
                // 于是营业执照跟着一起 permitAll —— UUID 文件名给的是"不可枚举"而不是访问控制，
                // URL 一旦进了数据库导出或运营端截图，谁都能拉到原件。
                // 现在四层目录把它们分开了，UploadResourceConfig 只放行 goods 那一层。
                //
                // /media/** 是私有资产（证件 / 售后凭证）：它同样不走 Bearer，
                // 但**不是不鉴权** —— 凭 URL 里的 HMAC 签名放行，有效期按分钟计。
                // 之所以不能用带 Bearer 的接口：<img> 与小程序的 <image> 都没法带请求头。
                .securityMatcher("/common/**", "/callback/**", "/actuator/**",
                        "/uploads/**", "/media/**")
                .cors(c -> c.configurationSource(corsSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }
}
