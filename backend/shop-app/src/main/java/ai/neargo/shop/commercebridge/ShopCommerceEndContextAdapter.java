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
    private final AccountSessionVerifier sessions;
    private final java.time.Clock clock;
    private final CommerceAccessInterceptor interceptor;
    private final CommerceRoutePolicy policy = new CommerceRoutePolicy(ShopStoreMemberRoutes.ALL);

    ShopCommerceEndContextAdapter(
            ShopCommerceStoreAdapter stores,
            BizIdentityResolver resolver,
            EndAccessFacade access,
            AccountSessionVerifier sessions,
            java.time.Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
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
        if (path.equals("/biz/business-types") || path.startsWith("/biz/business-types/")) {
            if (!account) throw new AccessDenied(AccessStatus.FORBIDDEN);
            // Catalog has no store target. Validate any explicitly supplied selection before
            // serving it, even for a legacy account that would normally fall back to its default.
            if (requested == null || requested.isBlank()) FoundationTarget.bindAccount(req);
            else checkFoundationSession(user, legacy, req, route, path, requested);
            return;
        }
        if (route.foundation()
                || path.equals("/biz/merchant/quick-start")
                || path.equals("/biz/auth/password")
                || path.equals("/biz/auth/identities/mine")
                || path.startsWith("/biz/merchant/store-claims/")) {
            checkFoundationSession(user, legacy, req, route, path, requested);
            if (route.foundation()) return;
        }
        if (policy.allowsAccountRecovery(
                route, req.getMethod(), path, requested, legacy.currentStoreNo())) return;
        if (route.lane() == CommerceRoutePolicy.Lane.LEGACY_PREVIEW) {
            BizContext.set(stores.legacyPreviewScope(user, legacy, requested, route.pathStore()));
            return;
        }
        if (!stores.affects(user, legacy, route, requested) && !route.newModelOnly()) return;
        // 登录的是账户；进店操作必须先选门店。没选 → 让端上去选店，而不是「联系管理员」
        if ((requested == null || requested.isBlank()) && route.lane() != CommerceRoutePolicy.Lane.DENY)
            throw AccessDenied.withNextAction(AccessStatus.FORBIDDEN, "SELECT_STORE");
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
        var strict = strictScope(user, requested, facts, route, path);
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

    private BizContext strictScope(
            LoginUser user,
            String requested,
            StoreSnapshot facts,
            CommerceRoutePolicy.Route route, String path) {
        if (facts.model() == CommerceModel.STORE_V1
                && (facts.businessTypeCode() == null || facts.businessTypeCode().isBlank()))
            throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        boolean procurementSetup = facts.status() == StoreStatus.SETUP
                && route.lane() == CommerceRoutePolicy.Lane.BOOTSTRAP
                && path.startsWith("/biz/stores/" + requested + "/mall/");
        if (facts.status() != StoreStatus.ACTIVE && !procurementSetup)
            throw new AccessDenied(AccessStatus.STORE_DISABLED);
        var strict = resolver.resolveStrict(user, requested);
        if (!requested.equals(strict.currentStoreNo())
                || !facts.merchantNo().equals(strict.merchantNo())
                || route.capability() != null && !strict.can(route.capability()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        return strict;
    }

    private void checkFoundationSession(
            LoginUser user,
            BizContext legacy,
            HttpServletRequest req,
            CommerceRoutePolicy.Route route,
            String path,
            String requested) {
        sessions.withFresh(
                        SecurityUtils.currentToken().orElse(null),
                        clock.instant(),
                        session -> {
                            var fresh = session.user();
                            if (user.subjectKind() != fresh.subjectKind()
                                    || !user.userNo().equals(fresh.userNo())
                                    || !user.tenantNo().equals(fresh.tenantNo()))
                                throw new AccessDenied(AccessStatus.ACCOUNT_INVALID);
                            if (!route.foundation() && !path.startsWith("/biz/business-types"))
                                return true;
                            boolean affects = stores.affects(fresh, legacy, route, requested);
                            if (!affects
                                    && !path.startsWith("/biz/stores/")
                                    && (requested == null || requested.isBlank())) return true;
                            if (requested == null
                                    || requested.isBlank()
                                    || route.pathStore() != null
                                            && !requested.equals(route.pathStore()))
                                throw new AccessDenied(AccessStatus.FORBIDDEN);
                            var strict =
                                    strictScope(fresh, requested, store(fresh, requested), route, path);
                            if (path.equals("/biz/upload/image")
                                    && !strict.owner()
                                    && !strict.can(BizPerms.STORE)
                                    && !strict.can(BizPerms.FINANCE))
                                throw new AccessDenied(AccessStatus.FORBIDDEN);
                            FoundationTarget.bind(req, strict);
                            BizContext.set(strict);
                            return true;
                        })
                .orElseThrow(() -> new AccessDenied(AccessStatus.ACCOUNT_INVALID));
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
