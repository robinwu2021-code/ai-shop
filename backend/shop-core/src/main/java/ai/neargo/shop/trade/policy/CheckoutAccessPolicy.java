package ai.neargo.shop.trade.policy;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** Every request and replay rechecks the current authenticated owner before any checkout lock. */
@Component
public class CheckoutAccessPolicy {
    private final TokenStores tokens;
    private final UserScopeFactsPort users;
    private final MerchantOperatorAccessPort merchants;
    private final BizIdentityResolver identities;

    /**
     * Use the current host token store and existing merchant access owners instead of caller
     * claims.
     */
    public CheckoutAccessPolicy(
            TokenStores tokens,
            UserScopeFactsPort users,
            MerchantOperatorAccessPort merchants,
            BizIdentityResolver identities) {
        this.tokens = tokens;
        this.users = users;
        this.merchants = merchants;
        this.identities = identities;
    }

    /** Recheck the actual consumer session and active account before opening checkout locks. */
    public String requireCurrentBuyer() {
        outside();
        LoginUser current = SecurityUtils.requireUser();
        if (current.realm() != Realm.CONSUMER
                || current.subjectKind() != SubjectKind.USR
                || !"MAIN".equals(current.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        String token =
                SecurityUtils.currentToken()
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
        LoginUser session =
                tokens.of(Realm.CONSUMER)
                        .get(token)
                        .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED))
                        .user();
        if (session == null
                || session.realm() != Realm.CONSUMER
                || session.subjectKind() != SubjectKind.USR
                || !"MAIN".equals(session.tenantNo())
                || !Objects.equals(current.userNo(), session.userNo())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (users.findActive("MAIN", current.userNo()).isEmpty()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return current.userNo();
    }

    /**
     * Reuse explicit namespace authorization and current store data scope for every request or
     * replay.
     */
    public MerchantActor requireMerchant(CheckoutScope scope, String permission) {
        outside();
        LoginUser user = SecurityUtils.requireUser();
        if (scope == null
                || user.realm() != Realm.MERCHANT
                || !"MAIN".equals(user.tenantNo())
                || !(user.subjectKind() == SubjectKind.MCH || user.subjectKind() == SubjectKind.USR)
                || !Objects.equals(scope.actorNo(), user.userNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var query =
                new MerchantOperatorAccessPort.Query(
                        scope.tenantNo(),
                        scope.entityNo(),
                        scope.storeNo(),
                        user.userNo(),
                        user.realm().name(),
                        user.subjectKind().name(),
                        permission);
        if (!merchants.allowed(query)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var context =
                DataScopeContext.executeWithoutScope(
                        () -> identities.resolve(user.userNo(), scope.storeNo()));
        var allowedStores = context.allowedStoresOrAll();
        if (!scope.entityNo().equals(context.merchantNo())
                || !scope.storeNo().equals(context.currentStoreNo())
                || allowedStores != null && !allowedStores.contains(scope.storeNo())
                || context.courierOnlyOrderView()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new MerchantActor(
                scope.tenantNo(),
                scope.entityNo(),
                scope.storeNo(),
                scope.actorNo(),
                user.realm().name(),
                user.subjectKind().name());
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Checkout authorization requires no caller transaction");
        }
    }

    /**
     * Canonical authenticated actor evidence is safe to freeze; it contains no token or contact
     * data.
     */
    public record MerchantActor(
            String tenantNo,
            String entityNo,
            String storeNo,
            String actorNo,
            String realm,
            String subjectKind) {}
}
