package ai.neargo.shop.platform.api.common;

import ai.neargo.shop.platform.AppVersionService;
import ai.neargo.shop.platform.dto.AppVersionVOs.AppVersionVO;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本检查（TDD-商户App问题回报与版本 §2 接口三）。
 *
 * <p><b>匿名、挂在 {@code /common} 而不是 {@code /biz}</b>：{@code /biz/**} 那条链要求登录，
 * 令牌过期的旧包读不到「你该升级了」—— 而它恰恰是最需要读到的那个。{@code /common/**} 已在
 * {@code SecurityConfig#publicChain} 放行、生产 nginx 也已放行，零部署改动；带不带 {@code Authorization} 都行，这里不读。
 *
 * <p>{@code @Profile("api")}：运营端部署里不存在这条路由。响应 {@code Cache-Control: no-store} ——
 * 强更是止血手段，不能被中间缓存拖住。
 */
@Profile("api")
@RestController
public class AppVersionController {

    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    /**
     * @param platform android / ios，不分大小写；缺失或不认得 → 10400
     * @param build    当前版本号（如 {@code 0.1.0}），可选；带了才回 {@code upgradeRequired}
     */
    @GetMapping("/common/app/version")
    public ResponseEntity<AppVersionVO> version(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String build,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String lang) {
        // 信封照包（ApiResponseWrapper 对 ResponseEntity 的 body 同样生效），这里只多一个响应头
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(appVersionService.current(platform, build, lang));
    }
}
