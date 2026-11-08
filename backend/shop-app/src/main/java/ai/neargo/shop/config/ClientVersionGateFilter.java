package ai.neargo.shop.config;

import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.AppVersionService;
import ai.neargo.shop.platform.dto.AppVersionVOs.UpgradeRequirement;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 旧版本写入被拒（TDD-商户App问题回报与版本 V7）：{@code /biz/**} 的写请求带了 {@code X-App-Platform} +
 * {@code X-App-Version} 且低于该平台的最低支持版 → HTTP 200、{@code code=10426}、{@code msg=CLIENT_UPGRADE_REQUIRED}、
 * {@code data={minSupported, storeUrl}}。
 *
 * <p><b>为什么要服务端拒写</b>：只靠 App 弹窗的话，被拦下的只有「会弹窗的新包」；真正有问题的老包
 * （比如退款金额算错的那一版）恰恰不认识那个弹窗。
 *
 * <p><b>不带头的请求放行</b>：uni-app 版 b-app 与现有 Flutter 包都不带，拦了就是全量误伤。读请求一律不拦。
 *
 * <p><b>不拦的七个写请求</b>（{@link #EXEMPT}，{@code ClientVersionGateFilterTest} 逐条断言）：
 * 让旧包能退出、能解绑推播、能在强更页上回报「升不了级」；四个登录类请求<b>有意不拦</b> ——
 * 登录不写业务数据，旧包登录后第一笔业务写入照样 10426，而回报要登录态，连登录都拦就再也报不了问题。
 * {@code POST /biz/auth/password}（设密码）是账号写入，照拦。
 *
 * <p>只挂在 {@code SecurityConfig#merchantChain}；不注册成 Spring bean —— 注册了 Servlet 容器会把它挂到所有路径上。
 */
public class ClientVersionGateFilter extends OncePerRequestFilter {

    static final String PLATFORM_HEADER = "X-App-Platform";
    static final String VERSION_HEADER = "X-App-Version";
    static final Set<String> EXEMPT = Set.of(
            "POST /biz/auth/logout",
            "POST /biz/push-token/unregister",
            "POST /biz/feedback",
            "POST /biz/auth/login",
            "POST /biz/auth/staff-login",
            "POST /biz/auth/otp/send",
            "POST /biz/auth/email-otp/send");
    private static final Set<String> WRITES = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AppVersionService appVersions;
    private final ObjectMapper json;

    public ClientVersionGateFilter(AppVersionService appVersions, ObjectMapper json) {
        this.appVersions = appVersions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String method = req.getMethod();
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (WRITES.contains(method) && !EXEMPT.contains(method + " " + path)) {
            Optional<UpgradeRequirement> block = appVersions.blockingUpgrade(
                    req.getHeader(PLATFORM_HEADER), req.getHeader(VERSION_HEADER));
            if (block.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(json.writeValueAsString(new ApiResult<>(
                        ErrorCode.CLIENT_UPGRADE_REQUIRED.code(), ErrorCode.CLIENT_UPGRADE_REQUIRED.name(),
                        block.get())));
                return;
            }
        }
        chain.doFilter(req, resp);
    }
}
