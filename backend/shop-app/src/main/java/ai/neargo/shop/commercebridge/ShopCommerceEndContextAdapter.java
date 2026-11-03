package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.access.EndAccessService;
import ai.neargo.commerce.starter.security.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.StorePaySettingService;

import jakarta.servlet.http.*;

import org.springframework.web.context.request.*;

import java.io.IOException;

public final class ShopCommerceEndContextAdapter
        implements BizContextFilter.Admission, StorePaySettingService.WriteAdmission {
    private static final String CHECK = ShopCommerceEndContextAdapter.class.getName() + ".check";
    static final String CLIENT = "X-Client-Id", PROOF = "X-End-Context";
    private final ShopCommerceStoreAdapter stores;
    private final BizIdentityResolver resolver;
    private final EndAccessService access;
    private final CommerceAccessInterceptor interceptor;
    private final CommerceRoutePolicy policy = new CommerceRoutePolicy();

    ShopCommerceEndContextAdapter(
            ShopCommerceStoreAdapter stores,
            BizIdentityResolver resolver,
            EndAccessService access) {
        this.stores = stores;
        this.resolver = resolver;
        this.access = access;
        this.interceptor = new CommerceAccessInterceptor(access);
    }

    public boolean admit(
            LoginUser user, BizContext legacy, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        try {
            select(user, legacy, req);
            return true;
        } catch (AccessDenied denied) {
            reject(resp, denied.status());
        } catch (RuntimeException unavailable) {
            reject(resp, AccessStatus.CHECK_UNAVAILABLE);
        }
        return false;
    }

    static int http(AccessStatus status) {
        return switch (status) {
            case ACCOUNT_INVALID -> 401;
            case CHECK_UNAVAILABLE -> 503;
            case CONTEXT_REFRESH_REQUIRED -> 409;
            default -> 403;
        };
    }

    static void reject(HttpServletResponse response, AccessStatus status) throws IOException {
        response.setStatus(http(status));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write(
                        "{\"code\":"
                                + errorCode(status)
                                + ",\"msg\":\""
                                + status.name()
                                + "\",\"data\":{\"nextAction\":\""
                                + AccessDecision.nextActionFor(status)
                                + "\"}}");
    }

    static int errorCode(AccessStatus status) {
        return switch (status) {
            case ACCOUNT_INVALID -> ErrorCode.TOKEN_EXPIRED.code();
            case CHECK_UNAVAILABLE -> ErrorCode.INTERNAL_ERROR.code();
            case FORBIDDEN -> ErrorCode.BIZ_ROLE_FORBIDDEN.code();
            default -> ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE.code();
        };
    }

    private StoreSnapshot store(LoginUser user, String no) {
        var ref = new StoreRef(user.tenantNo(), no);
        return stores.find(ref)
                .filter(s -> ref.equals(s.store()))
                .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
    }

    private void select(LoginUser user, BizContext legacy, HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        var route = policy.match(req.getMethod(), path);
        String requested = req.getHeader(BizContextFilter.STORE_HEADER);
        boolean account = route.lane() == CommerceRoutePolicy.Lane.ACCOUNT;
        if (account
                && (requested == null
                        || requested.isBlank()
                        || requested.equals(legacy.currentStoreNo()))) return;
        if (!stores.affects(user, requested, legacy.currentStoreNo(), route.pathStore())
                && !path.startsWith("/biz/stores/")) return;
        if (requested == null
                || requested.isBlank()
                || account
                || route.lane() == CommerceRoutePolicy.Lane.DENY
                || route.pathStore() != null && !requested.equals(route.pathStore()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        var facts = store(user, requested);
        if (facts.model() == CommerceModel.LEGACY) {
            if (path.startsWith("/biz/stores/") || !requested.equals(legacy.currentStoreNo()))
                throw new AccessDenied(AccessStatus.FORBIDDEN);
            return;
        }
        if (facts.businessTypeCode() == null || facts.businessTypeCode().isBlank())
            throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        if (facts.status() != StoreStatus.ACTIVE)
            throw new AccessDenied(AccessStatus.STORE_DISABLED);
        var strict = resolver.resolveStrict(user, requested);
        if (!requested.equals(strict.currentStoreNo())
                || !facts.merchantNo().equals(strict.merchantNo())
                || route.capability() != null && !strict.can(route.capability()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        BizContext.set(strict);
        if (route.lane() == CommerceRoutePolicy.Lane.END_PROTECTED) {
            var actor = ShopCommerceActorAdapter.current();
            var check =
                    interceptor.authorize(
                            route,
                            actor,
                            facts.store(),
                            new StoreRef(actor.tenantNo(), strict.currentStoreNo()),
                            req.getHeader(CLIENT),
                            req.getHeader(PROOF));
            req.setAttribute(CHECK, check);
        }
    }

    public void require(String storeNo) {
        var request = RequestContextHolder.getRequestAttributes();
        Object admission =
                request instanceof ServletRequestAttributes attrs
                        ? attrs.getRequest().getAttribute(CHECK)
                        : null;
        if (!(admission instanceof AccessCheck check)) {
            if (stores.current(new StoreRef("MAIN", storeNo)).model() == CommerceModel.LEGACY)
                return;
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        }
        if (!storeNo.equals(check.requestedStore().storeNo())
                || !"biz:store:admin".equals(check.requiredCapability())
                || !CommerceRoutePolicy.BIZ.equals(check.entitlementCode()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        access.requireForWrite(check);
    }

    public IssueEndContextCommand command(String store, String end, String client) {
        var actor = ShopCommerceActorAdapter.current();
        if (end == null || client == null || client.isBlank())
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        String code =
                switch (end) {
                    case "BIZ_APP" -> CommerceRoutePolicy.BIZ;
                    case "APP_POS" -> CommerceRoutePolicy.POS;
                    default -> throw new AccessDenied(AccessStatus.FORBIDDEN);
                };
        return new IssueEndContextCommand(
                actor,
                new StoreRef(actor.tenantNo(), store),
                new StoreRef(actor.tenantNo(), BizContext.requireStoreNo()),
                end,
                code,
                client,
                EndAccessService.AUDIENCE);
    }
}
