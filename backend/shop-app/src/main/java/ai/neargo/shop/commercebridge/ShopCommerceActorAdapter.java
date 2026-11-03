package ai.neargo.shop.commercebridge;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.EndAccessAuthorityPort;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;
import ai.neargo.shop.auth.*;

import java.time.Instant;

final class ShopCommerceActorAdapter implements EndAccessAuthorityPort {
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

    static ActorContext current() {
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

    public Authority verify(
            ActorContext actor,
            StoreRef store,
            String entitlement,
            String capability,
            Instant now) {
        if (!current().equals(actor)) throw new AccessDenied(AccessStatus.FORBIDDEN);
        return sessions.withFresh(
                        bearer(),
                        now,
                        session -> {
                            var user = session.user();
                            if (!user.userNo().equals(actor.principalNo())
                                    || user.subjectKind()
                                            != SecurityUtils.requireUser().subjectKind()
                                    || !user.tenantNo().equals(store.tenantNo())
                                    || !actor.accountSessionFingerprint()
                                            .equals(session.sessionFingerprint()))
                                throw new AccessDenied(AccessStatus.ACCOUNT_INVALID);
                            var facts =
                                    stores.find(store)
                                            .orElseThrow(
                                                    () -> new AccessDenied(AccessStatus.FORBIDDEN));
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
                        })
                .orElseThrow(() -> new AccessDenied(AccessStatus.ACCOUNT_INVALID));
    }
}
