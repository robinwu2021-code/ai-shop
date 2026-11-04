package ai.neargo.shop.commercebridge;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.foundation.FoundationAuthorityPort;
import ai.neargo.commerce.api.port.EndAccessAuthorityPort;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;
import ai.neargo.shop.auth.*;

import java.time.Instant;

public final class ShopCommerceActorAdapter
        implements EndAccessAuthorityPort, FoundationAuthorityPort {
    private final AccountSessionVerifier sessions;
    private final BizIdentityResolver resolver;
    private final ShopCommerceStoreAdapter stores;

    ShopCommerceActorAdapter(
            AccountSessionVerifier sessions,
            BizIdentityResolver resolver,
            ShopCommerceStoreAdapter stores) {
        this.sessions = sessions;
        this.resolver = resolver;
        this.stores = stores;
    }

    public static ActorContext current() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT || !"MAIN".equals(user.tenantNo()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        return new ActorContext(
                user.tenantNo(), SubjectKind.ACCOUNT, user.userNo(), TokenHash.of(bearer()));
    }

    private static String bearer() {
        return SecurityUtils.currentToken()
                .orElseThrow(() -> new AccessDenied(AccessStatus.ACCOUNT_INVALID));
    }

    private <T> T fresh(
            ActorContext actor,
            Instant now,
            java.util.function.Function<AccountSessionVerification, T> read) {
        if (!current().equals(actor)) throw new AccessDenied(AccessStatus.FORBIDDEN);
        return sessions.withFresh(
                        bearer(),
                        now,
                        session -> {
                            var user = session.user();
                            if (!user.userNo().equals(actor.principalNo())
                                    || user.subjectKind()
                                            != SecurityUtils.requireUser().subjectKind()
                                    || !user.tenantNo().equals(actor.tenantNo())
                                    || !actor.accountSessionFingerprint()
                                            .equals(session.sessionFingerprint()))
                                throw new AccessDenied(AccessStatus.ACCOUNT_INVALID);
                            return read.apply(session);
                        })
                .orElseThrow(() -> new AccessDenied(AccessStatus.ACCOUNT_INVALID));
    }

    public Instant account(ActorContext actor, Instant now) {
        return fresh(actor, now, AccountSessionVerification::expiresAt);
    }

    public Member member(ActorContext actor, StoreRef store, Instant now) {
        if (!actor.tenantNo().equals(store.tenantNo()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        return fresh(
                actor,
                now,
                session -> {
                    var facts =
                            stores.details(store)
                                    .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
                    var ctx = resolver.resolveStrict(session.user(), store.storeNo());
                    if (!store.equals(facts.snapshot().store())
                            || !store.storeNo().equals(ctx.currentStoreNo())
                            || !ctx.storeNos().contains(store.storeNo())
                            || !facts.snapshot().merchantNo().equals(ctx.merchantNo()))
                        throw new AccessDenied(AccessStatus.FORBIDDEN);
                    return new Member(
                            facts.snapshot(),
                            facts.storeStatus(),
                            facts.entityStatus(),
                            ctx.owner(),
                            ctx.staffRoles().stream().sorted().toList(),
                            ctx.can(CommerceRoutePolicy.PROCUREMENT_DUTY),
                            session.expiresAt());
                });
    }

    public Authority verify(
            ActorContext actor,
            StoreRef store,
            String entitlement,
            String capability,
            Instant now) {
        if (!actor.tenantNo().equals(store.tenantNo()))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        return fresh(
                actor,
                now,
                session -> {
                    var user = session.user();
                    var facts =
                            stores.find(store)
                                    .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
                    var ctx = resolver.resolveStrict(user, store.storeNo());
                    String entry = CommerceRoutePolicy.entryPermission(entitlement);
                    boolean selected =
                            facts.merchantNo().equals(ctx.merchantNo())
                                    && store.storeNo().equals(ctx.currentStoreNo());
                    boolean allowed =
                            selected
                                    && ctx.can(entry)
                                    && (EndAccessFacade.ENTER_END.equals(capability)
                                            || ctx.can(capability));
                    return new Authority(
                            facts,
                            session.expiresAt(),
                            allowed,
                            selected && ctx.can(CommerceRoutePolicy.PROCUREMENT_DUTY));
                });
    }
}
