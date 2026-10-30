package ai.neargo.shop.auth;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Machine handlers call only after token validation; restore the exact previous thread context on every exit. */
public final class DelegatedIdentityContext {
    private DelegatedIdentityContext() { }
    public static DelegatedIdentity current() {
        outside();
        var user = SecurityUtils.requireUser();
        return new DelegatedIdentity(user.tenantNo(), user.realm().name(), user.subjectKind().name(), user.userNo());
    }
    public static DelegatedIdentity current(String actor) {
        var identity = current();
        if (!identity.actorNo().equals(actor)) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return identity;
    }
    public static <T> T call(DelegatedIdentity identity, Supplier<T> operation) {
        outside();
        if (identity == null) { throw BizException.of(ErrorCode.FORBIDDEN); }
        LoginUser user = "CONSUMER".equals(identity.realm())
                ? LoginUser.consumer(identity.actorNo(), identity.actorNo())
                : "USR".equals(identity.subjectKind()) ? LoginUser.merchantByUser(identity.actorNo(),
                        identity.actorNo())
                : LoginUser.merchant(identity.actorNo(), identity.actorNo());
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            SecurityContextHolder.setContext(context);
            return operation.get();
        } finally { SecurityContextHolder.setContext(previous); }
    }
    public static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("machine delegation requires no transaction");
        }
    }
}
