package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * <b>CORS 白名单里必须有生产站点，不能只有 localhost。</b>
 *
 * <p>放在 {@code config} 包下而不是 {@code arch}：{@code corsSource()} 是包级私有，
 * 为了测试把它改成 public 是<b>为验证放宽生产代码的可见性</b> —— 反了。
 * 测试跟着被测对象走，代价只是它不在 arch 那一堆里。
 *
 * <h2>它防的是一个躺了很久的缺陷</h2>
 * 2026-09-01 查明：白名单里<b>一条生产域名都没有</b>，只有开发端口。
 * 于是线上运营端从来没能登录过 —— nginx 日志里
 * {@code POST /ops/auth/login → 403}，响应体 {@code Invalid CORS request}。
 *
 * <p>它为什么能躺这么久，值得记下来：
 * <ul>
 *   <li><b>后端直连一直是 200</b>：{@code curl localhost:8081} 不带 Origin，
 *       也不受同源策略约束；</li>
 *   <li><b>本地开发一直正常</b>：开发端口在白名单里；</li>
 *   <li><b>类注释里写着「+ 生产同源」</b> —— 那一半从来不存在，
 *       而读注释的人（包括我）会以为它在。</li>
 * </ul>
 *
 * <p>「同源就不用配 CORS」是个误解：<b>浏览器发的每个 POST/fetch 都带 Origin，
 * 同源也带</b>，{@code CorsFilter} 一样会检查。
 *
 * <h2>判据取「有没有 https 来源」而不是逐条比域名</h2>
 * 比死域名的话，换域名那天这条闸门会先红一次，而它拦的不是问题本身。
 * 真正要防的是「白名单里只有开发环境」—— 生产必然是 https，
 * 开发端口必然是 http://localhost。有没有 https 条目，就是这两者的分界。
 */
class CorsAllowsProductionTest {

    @Test
    @DisplayName("★★★ CORS 白名单必须含生产来源 —— 只有 localhost 的话，浏览器一上线就全是 403")
    void corsWhitelistIncludesProductionOrigin() {
        var src = (UrlBasedCorsConfigurationSource) new SecurityConfig().corsSource();
        CorsConfiguration cfg = src.getCorsConfigurations().get("/**");

        assertThat(cfg)
                .as("`/**` 上没有 CORS 配置 —— 注册路径改了？那这条闸门量的是空气")
                .isNotNull();

        List<String> origins = cfg.getAllowedOrigins();
        assertThat(origins)
                .as("白名单是空的 —— 空集在 Spring 里等于「谁都不许」，全线 403")
                .isNotNull()
                .isNotEmpty();

        /*
         * **对照量**：开发端口必须还在。只断言「有 https」的话，
         * 有人把整个列表换成一条生产域名也能过，而那会让本地开发全挂 ——
         * 一条闸门不该在防住 A 的同时给 B 开门。
         */
        assertThat(origins)
                .as("本地开发端口不在白名单里了 —— 那本地开发会全线 403")
                .anyMatch(o -> o.startsWith("http://localhost"));

        assertThat(origins.stream().filter(o -> o.startsWith("https://")).toList())
                .as("白名单里没有任何 https 来源 —— 也就是**只有开发环境**。\n"
                        + "  生产必然是 https，而浏览器发的每个 POST 都带 Origin（同源也带），\n"
                        + "  少了它线上不会报错，只会全线 403 + 「Invalid CORS request」，\n"
                        + "  而请求在到达业务逻辑之前就被拦掉，日志里连一条失败记录都没有。\n"
                        + "  换域名请改 SecurityConfig 的 PROD_ORIGINS，或配 shop.cors.extra-origins。")
                .isNotEmpty();
    }
}
