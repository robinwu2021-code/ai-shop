package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
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
    private final EndAccessFacade access;
    private final CommerceAccessInterceptor interceptor;
    private final CommerceRoutePolicy policy = new CommerceRoutePolicy();

    ShopCommerceEndContextAdapter(
            ShopCommerceStoreAdapter stores, BizIdentityResolver resolver, EndAccessFacade access) {
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
            reject(resp, denied);
        } catch (RuntimeException unavailable) {
            reject(resp, new AccessDenied(AccessStatus.CHECK_UNAVAILABLE));
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

    static void reject(HttpServletResponse response, AccessDenied denied) throws IOException {
        var status = denied.status();
        response.setStatus(http(status));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write(
                        "{\"code\":"
                                + errorCode(status)
                                + ",\"msg\":\""
                                + status.name()
                                + "\",\"data\":{\"nextAction\":\""
                                + denied.nextAction()
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
        if (policy.allowsAccountRecovery(
                route, req.getMethod(), path, requested, legacy.currentStoreNo())) return;
        if (route.lane() == CommerceRoutePolicy.Lane.LEGACY_PREVIEW) {
            BizContext.set(stores.legacyPreviewScope(user, legacy, requested, route.pathStore()));
            return;
        }
        if (!stores.affects(user, legacy, requested, legacy.currentStoreNo(), route.pathStore())
                && !route.newModelOnly()) return;
        if (requested == null
                || requested.isBlank()
                || route.lane() == CommerceRoutePolicy.Lane.DENY
                || route.pathStore() != null && !requested.equals(route.pathStore()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        var facts = store(user, requested);
        if (facts.model() == CommerceModel.LEGACY && !account) {
            if (route.newModelOnly() || !requested.equals(legacy.currentStoreNo()))
                throw new AccessDenied(AccessStatus.FORBIDDEN);
            return;
        }
        if (facts.model() == CommerceModel.STORE_V1
                && (facts.businessTypeCode() == null || facts.businessTypeCode().isBlank()))
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
                CommerceRoutePolicy.entitlementForEnd(end)
                        .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
        return new IssueEndContextCommand(
                actor,
                new StoreRef(actor.tenantNo(), store),
                new StoreRef(actor.tenantNo(), BizContext.requireStoreNo()),
                end,
                code,
                client,
                EndAccessFacade.AUDIENCE);
    }
}
