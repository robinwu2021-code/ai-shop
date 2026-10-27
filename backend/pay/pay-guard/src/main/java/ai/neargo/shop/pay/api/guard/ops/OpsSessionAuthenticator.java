package ai.neargo.shop.pay.api.guard.ops;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import ai.neargo.shop.pay.api.guard.core.PortalAuthFilter;
import ai.neargo.shop.pay.api.guard.core.PortalAuthenticator;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;

/**
 * 支付运营端入口（ADR-044 §2-4 内嵌形态）：复用主应用的运营会话 —— 运营链上的令牌过滤器已认过人，
 * 这里只确认「是运营令牌」并给出调用方（权限码由各端点的 {@code @perm.can} 判），让接入层照样写请求记录。
 */
@ConditionalOnGuardEntry(PortalFace.OPS)
@Component
public class OpsSessionAuthenticator implements PortalAuthenticator {

    @Override
    public PortalFace face() {
        return PortalFace.OPS;
    }

    @Override
    public Optional<PortalPrincipal> authenticate(HttpServletRequest request) {
        Optional<LoginUser> who = SecurityUtils.currentUser().filter(u -> u.realm() == Realm.OPERATOR);
        if (who.isEmpty()) {
            request.setAttribute(PortalAuthFilter.FAILURE, "NO_OPERATOR_SESSION");
        }
        return who.map(u -> new PortalPrincipal(PortalFace.OPS, "OPERATOR", u.userNo(), null,
                u.perms() == null ? Set.of() : Set.copyOf(u.perms())));
    }
}
