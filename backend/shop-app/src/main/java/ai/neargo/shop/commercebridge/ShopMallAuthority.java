package ai.neargo.shop.commercebridge;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.starter.http.*;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.FreshAccountReadUnavailable;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;

/**
 * HTTP and core checks re-read the same live identity, never the authentication's permission
 * snapshot.
 */
final class ShopMallAuthority
        implements MallHttpContextPort, ActorAuthorizationPort, CatalogAuthorizationPort {
    private final AccountSessionVerifier buyers;
    private final AccountSessionVerifier operators;
    private final BizIdentityResolver resolver;
    private final ShopCommerceStoreAdapter stores;
    private final Clock clock;
    private final ShopMallPayerBindings bindings;

    ShopMallAuthority(
            AccountSessionVerifier buyers,
            AccountSessionVerifier operators,
            BizIdentityResolver resolver,
            ShopCommerceStoreAdapter stores,
            Clock clock) {
        this(buyers, operators, resolver, stores, clock, ShopMallPayerBindings.UNAVAILABLE);
    }

    ShopMallAuthority(
            AccountSessionVerifier buyers,
            AccountSessionVerifier operators,
            BizIdentityResolver resolver,
            ShopCommerceStoreAdapter stores,
            Clock clock,
            ShopMallPayerBindings bindings) {
        this.bindings = bindings;
        this.buyers = buyers;
        this.operators = operators;
        this.resolver = resolver;
        this.stores = stores;
        this.clock = clock;
    }

    private static AccessDenied denied(AccessStatus status) {
        return new AccessDenied(status);
    }

    private static ActorContext actor(LoginUser user, String fingerprint) {
        return new ActorContext(
                "MAIN",
                user.realm() == Realm.OPERATOR ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT,
                user.realm() == Realm.OPERATOR ? user.userNo() : ShopMallIdentity.buyerKey(user),
                fingerprint,
                new RawPrincipalIdentity(
                        user.realm().name(), user.subjectKind().name(), user.userNo()));
    }

    private <T> T fresh(Realm realm, Function<AccountSessionVerification, T> action) {
        var supplied =
                SecurityUtils.currentUser().orElseThrow(() -> denied(AccessStatus.ACCOUNT_INVALID));
        if (supplied.realm() != realm
                || !"MAIN".equals(supplied.tenantNo())
                || realm == Realm.OPERATOR
                        && supplied.subjectKind() != ai.neargo.auth.store.SubjectKind.OPS)
            throw denied(AccessStatus.FORBIDDEN);
        String token =
                SecurityUtils.currentToken()
                        .orElseThrow(() -> denied(AccessStatus.ACCOUNT_INVALID));
        try {
            return (realm == Realm.OPERATOR ? operators : buyers)
                    .withFresh(
                            token,
                            clock.instant(),
                            session -> {
                                var user = session.user();
                                if (user.realm() != realm
                                        || !"MAIN".equals(user.tenantNo())
                                        || user.subjectKind() != supplied.subjectKind()
                                        || !user.userNo().equals(supplied.userNo())
                                        || !TokenHash.of(token)
                                                .equals(session.sessionFingerprint()))
                                    throw denied(AccessStatus.ACCOUNT_INVALID);
                                return action.apply(session);
                            })
                    .orElseThrow(() -> denied(AccessStatus.ACCOUNT_INVALID));
        } catch (FreshAccountReadUnavailable
                | org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException
                | UnsupportedOperationException unavailable) {
            throw denied(AccessStatus.CHECK_UNAVAILABLE);
        }
    }

    private static boolean opsPermission(LoginUser user, String capability) {
        String code =
                "commerce:mall:refund:approve".equals(capability)
                        ? "commerce:refund:review"
                        : capability;
        return ai.neargo.common.security.rbac.Permissions.matches(user.grantedPermissions(), code);
    }

    public ActorContext operator(String capability) {
        return fresh(
                Realm.OPERATOR,
                session -> {
                    if (!opsPermission(session.user(), capability))
                        throw denied(AccessStatus.FORBIDDEN);
                    return actor(session.user(), session.sessionFingerprint());
                });
    }

    public Buyer buyer(String storeNo, String capability) {
        if (storeNo == null || !storeNo.matches("[A-Za-z0-9_-]{1,64}"))
            throw denied(AccessStatus.FORBIDDEN);
        var ref = new StoreRef("MAIN", storeNo);
        return fresh(
                Realm.MERCHANT,
                session -> {
                    var current = actor(session.user(), session.sessionFingerprint());
                    if (!buyerDecision(session.user(), ref, capability).allowed())
                        throw denied(AccessStatus.FORBIDDEN);
                    return new Buyer(current, ref);
                });
    }

    private AuthorizationDecision buyerDecision(LoginUser user, StoreRef ref, String capability) {
        if (!"MAIN".equals(ref.tenantNo())) throw denied(AccessStatus.FORBIDDEN);
        String selected = BizContext.current().currentStoreNo();
        if (selected != null && !selected.isBlank() && !selected.equals(ref.storeNo()))
            throw denied(AccessStatus.FORBIDDEN);
        var facts = stores.find(ref).orElseThrow(() -> denied(AccessStatus.FORBIDDEN));
        var scope = resolver.resolveStrict(user, ref.storeNo());
        if (!ref.storeNo().equals(scope.currentStoreNo())
                || !scope.storeNos().contains(ref.storeNo())
                || !facts.merchantNo().equals(scope.merchantNo()))
            throw denied(AccessStatus.FORBIDDEN);
        if (facts.status() != StoreStatus.ACTIVE) throw denied(AccessStatus.STORE_DISABLED);
        boolean permitted =
                switch (capability) {
                    case "commerce:mall:browse" -> true;
                    case "commerce:mall:read",
                                    "commerce:mall:purchase",
                                    "commerce:mall:refund:request" ->
                            scope.can(CommerceRoutePolicy.PROCUREMENT_DUTY);
                    default -> false;
                };
        return new AuthorizationDecision(
                permitted,
                scope.owner() ? "OWNER" : "MEMBER",
                permitted ? Optional.empty() : Optional.of("FORBIDDEN"));
    }

    public boolean allowed(ActorContext expected, String capability) {
        return fresh(
                Realm.OPERATOR,
                session ->
                        actor(session.user(), session.sessionFingerprint()).equals(expected)
                                && opsPermission(session.user(), capability));
    }

    public AuthorizationDecision authorize(ActorContext expected, StoreRef ref, String capability) {
        if (expected.subjectKind() == SubjectKind.OPERATOR) {
            boolean permitted = "MAIN".equals(ref.tenantNo()) && allowed(expected, capability);
            return new AuthorizationDecision(
                    permitted, "OPS", permitted ? Optional.empty() : Optional.of("FORBIDDEN"));
        }
        return fresh(
                Realm.MERCHANT,
                session -> {
                    if (!actor(session.user(), session.sessionFingerprint()).equals(expected))
                        throw denied(AccessStatus.FORBIDDEN);
                    return buyerDecision(session.user(), ref, capability);
                });
    }

    public String payerReference(String channel, String method) {
        return fresh(
                Realm.MERCHANT,
                session -> {
                    if ("INTERNAL".equals(channel) && "ZERO".equals(method)) return null;
                    var current = actor(session.user(), session.sessionFingerprint());
                    var proof =
                            bindings.current(current, channel, method)
                                    .orElseThrow(
                                            () ->
                                                    new ai.neargo.commerce.api.mall.MallException(
                                                            "PAYER_BINDING_UNPROVEN"));
                    if (!current.rawIdentity().equals(proof.identity())
                            || proof.reference() == null
                            || proof.reference().isBlank())
                        throw new ai.neargo.commerce.api.mall.MallException(
                                "PAYER_BINDING_UNPROVEN");
                    return proof.reference();
                });
    }
}
