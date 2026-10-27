package ai.neargo.shop.pay.api.guard.checkout;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import ai.neargo.shop.pay.api.guard.core.PortalAuthenticator;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;

/**
 * 收银台入口（安全规范 §2.5）：顾客不登录也能付，凭证是路径里的收银令牌 / 码号，由收银台服务逐次校验。
 * 这里只把来访者认成「访客」（主体号是来源 IP），过滤器照样写请求记录 —— 令牌在记录里只留哈希前缀。
 */
@ConditionalOnGuardEntry(PortalFace.CHECKOUT)
@Component
public class CheckoutVisitorAuthenticator implements PortalAuthenticator {

    @Override
    public PortalFace face() {
        return PortalFace.CHECKOUT;
    }

    @Override
    public Optional<PortalPrincipal> authenticate(HttpServletRequest request) {
        String ip = ClientMeta.clientIp(request);
        return Optional.of(new PortalPrincipal(PortalFace.CHECKOUT, "VISITOR", ip == null ? "unknown" : ip, null, Set.of()));
    }
}
