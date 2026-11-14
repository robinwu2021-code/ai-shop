package ai.neargo.shop.pay.api.guard.biz;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;

/**
 * 商家端入口（ADR-044 §2-4 内嵌形态）：复用主应用的商家会话 —— 商家链上的令牌过滤器已认过人、{@code BizContextFilter}
 * 已解析好门店作用域，这里只确认「是商家令牌」并给出调用方，让接入层照样写请求记录。认不出（没登录、跨端令牌）就 401。
 */
@ConditionalOnGuardEntry(PortalFace.BIZ)
@Component
public class BizSessionAuthenticator implements EntryGuard {

    @Override
    public PortalFace entry() {
        return PortalFace.BIZ;
    }

    @Override
    public GuardResult authenticate(HttpServletRequest request) {
        Optional<LoginUser> who = SecurityUtils.currentUser().filter(u -> u.realm() == Realm.MERCHANT);
        return who.map(u -> GuardResult.ok(new PortalPrincipal(PortalFace.BIZ, "MCH_ACCOUNT", u.userNo(), null,
                        u.perms() == null ? Set.of() : Set.copyOf(u.perms()))))
                .orElseGet(() -> GuardResult.unauthorized("NO_MERCHANT_SESSION"));
    }
}
