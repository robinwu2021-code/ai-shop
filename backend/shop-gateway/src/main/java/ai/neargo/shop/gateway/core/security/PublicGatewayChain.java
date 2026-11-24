package ai.neargo.shop.gateway.core.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/** 不属于任何一端的公共地址：没有令牌认证，所以不走端基类。 */
public class PublicGatewayChain {

    /** 公共与回调：{@code /callback/**} 各自验签，不走 Bearer（[API 清单 §5.2]）。 */
    @Bean
    @Profile({"api", "ops"})
    @Order(4)
    SecurityFilterChain publicChain(HttpSecurity http, @Qualifier("corsSource") CorsConfigurationSource cors)
            throws Exception {
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
                // /s/** 是店铺码短链（TDD-店铺码与分享 §3.5）：扫码的人多数没登录，
                // 这一跳只解析码并 302 到门店页，不读任何用户数据。
                // /l/** 是通用短链（TDD-收件人物流触达 §4.2）：s.hxmall.top 转进来，
                // 解短码 302 到微信 URL Link；收件人同样没登录。与 /s 分开，理由见 ShortLinkController。
                // **显式写出来**：不写就是靠「碰巧没有一条链匹配它所以放行」，
                // 而那种性质会在有人把 securityMatcher 改宽时静默消失。
                .securityMatcher("/common/**", "/callback/**", "/actuator/**",
                        "/uploads/**", "/media/**", "/s/**", "/l/**", "/public/invoice-deliveries/**")
                .cors(c -> c.configurationSource(cors))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }
}
