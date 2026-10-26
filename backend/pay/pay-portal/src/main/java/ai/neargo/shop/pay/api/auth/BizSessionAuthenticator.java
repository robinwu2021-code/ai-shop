package ai.neargo.shop.pay.api.auth;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.api.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 商家端入口（ADR-044 §2-4 内嵌形态）：复用主应用的商家会话 —— 商家链上的令牌过滤器已认过人、{@code BizContextFilter}
 * 已解析好门店作用域，这里只确认「是商家令牌」并给出调用方，让接入层照样写请求记录。认不出（没登录、跨端令牌）就 401。
 */
@Component
public class BizSessionAuthenticator implements PortalAuthenticator {

    @Override
    public PortalFace face() {
        return PortalFace.BIZ;
    }

    @Override
    public Optional<PortalPrincipal> authenticate(HttpServletRequest request) {
        Optional<LoginUser> who = SecurityUtils.currentUser().filter(u -> u.realm() == Realm.MERCHANT);
        if (who.isEmpty()) {
            request.setAttribute(PortalAuthFilter.FAILURE, "NO_MERCHANT_SESSION");
        }
        return who.map(u -> new PortalPrincipal(PortalFace.BIZ, "MCH_ACCOUNT", u.userNo(), null,
                u.perms() == null ? Set.of() : Set.copyOf(u.perms())));
    }
}
