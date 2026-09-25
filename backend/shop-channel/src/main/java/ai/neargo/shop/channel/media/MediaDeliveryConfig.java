package ai.neargo.shop.channel.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 公开图走哪个出口（ADR-026，TDD-图片走服务器与流量切换）。
 *
 * <p>库里只存<b>规范地址</b>（{@code shop.cos.domain}，生产 {@code https://img.hxmall.top}，经应用服务器）。
 * {@code shop.media.delivery}：
 * <ul>
 *   <li>{@code server}（默认）：客户端拿到的就是规范地址。过滤器注册但<b>不启用</b>，零开销；</li>
 *   <li>{@code direct}：经图片服务器。{@link MediaHostRewriteFilter} 在 HTTP 出入口换前缀。</li>
 * </ul>
 *
 * <p><b>为什么只在 provider=cos 时生效</b>：本地盘 provider 的地址是站内相对路径，没有「另一个出口」可言。
 *
 * <p><b>配错就拒绝启动</b>：{@code direct} 而 {@code direct-base-url} 或规范前缀为空，
 * 继续跑的话过滤器要么什么都不换（以为切了、其实没切），要么把地址换成空前缀（全站裂图）。
 * 两种都不报错，所以在这里挡。
 */
@Configuration
@ConditionalOnProperty(name = "shop.media.provider", havingValue = "cos")
public class MediaDeliveryConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaDeliveryConfig.class);

    public static final String SERVER = "server";
    public static final String DIRECT = "direct";

    @Bean
    FilterRegistrationBean<MediaHostRewriteFilter> mediaHostRewriteFilter(
            @Value("${shop.media.delivery:server}") String delivery,
            @Value("${shop.media.direct-base-url:}") String directBaseUrl,
            @Value("${shop.cos.domain:}") String canonicalBaseUrl) {
        String mode = delivery == null ? SERVER : delivery.trim().toLowerCase();
        String canonical = trimSlash(canonicalBaseUrl);
        String direct = trimSlash(directBaseUrl);

        if (!SERVER.equals(mode) && !DIRECT.equals(mode)) {
            throw new IllegalStateException("shop.media.delivery 只能是 server 或 direct，实际是：" + delivery);
        }
        if (DIRECT.equals(mode)) {
            if (direct.isEmpty()) {
                throw new IllegalStateException("shop.media.delivery=direct 但 SHOP_MEDIA_DIRECT_BASE_URL 为空"
                        + " —— 配上图片服务器的域名（如 https://cdn.hxmall.top）再启动");
            }
            if (canonical.isEmpty()) {
                throw new IllegalStateException("shop.media.delivery=direct 但 COS_DOMAIN（规范地址前缀）为空"
                        + " —— 不知道要把哪个前缀换出去");
            }
            if (canonical.equals(direct)) {
                throw new IllegalStateException("shop.media.delivery=direct 但 direct-base-url 与 COS_DOMAIN 相同："
                        + direct + " —— 这等于没切");
            }
        }

        MediaHostRewriteFilter filter = new MediaHostRewriteFilter(canonical + "/", direct + "/");
        FilterRegistrationBean<MediaHostRewriteFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(DIRECT.equals(mode));
        reg.addUrlPatterns("/mp/*", "/biz/*", "/ops/*");
        // 排在安全过滤器之后也无妨（它只改请求体与响应体），但要在最外层包住响应，才看得到完整的 body
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        log.info("[media] 公开图出口={} base={}", mode, DIRECT.equals(mode) ? direct : canonical);
        return reg;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
