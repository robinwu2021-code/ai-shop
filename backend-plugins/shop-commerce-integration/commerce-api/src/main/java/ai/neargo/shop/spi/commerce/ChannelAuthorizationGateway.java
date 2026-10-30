package ai.neargo.shop.spi.commerce;

import java.time.Instant;
import java.util.Set;

/** 获准协议的授权适配器；凭据必须由原安全存储持有，不把token带入本域。没有默认实现。 */
public interface ChannelAuthorizationGateway {
    String profileNo();

    Set<String> returnTargetCodes();

    Set<String> authorizationOrigins();

    Set<String> callbackUris();

    Start begin(AuthorizationContext context);

    Grant exchange(AuthorizationContext context, String code);

    Revocation revoke(RevocationContext context);

    /**
     * Read-only original-key recovery never repeats a one-use code exchange. Legacy adapters stay
     * UNKNOWN.
     */
    default AuthorizationRecovery queryAuthorization(AuthorizationQuery query) {
        return new AuthorizationRecovery(query, RecoveryStatus.UNKNOWN, null, null, null);
    }

    default RevocationRecovery queryRevocation(RevocationContext query) {
        return new RevocationRecovery(query, RecoveryStatus.UNKNOWN, null, null);
    }

    enum RecoveryStatus {
        FOUND,
        UNKNOWN,
        TERMINAL_FAILED
    }

    record AuthorizationQuery(
            String operationNo,
            String entityNo,
            String accountNo,
            String profileNo,
            String externalAccountRef,
            String actorNo,
            String returnTargetCode,
            boolean callbackConsumed) {}

    record AuthorizationRecovery(
            AuthorizationQuery query,
            RecoveryStatus status,
            Start start,
            Grant grant,
            String originalReceiptRef) {}

    record RevocationRecovery(
            RevocationContext query,
            RecoveryStatus status,
            Revocation revocation,
            String originalReceiptRef) {}

    record AuthorizationContext(
            String operationNo,
            String entityNo,
            String accountNo,
            String profileNo,
            String externalAccountRef,
            String actorNo,
            String state,
            String returnTargetCode) {}

    record RevocationContext(
            String operationNo,
            String entityNo,
            String accountNo,
            String profileNo,
            String externalAccountRef,
            String authorizationNo,
            String credentialRef) {}

    record Start(String authorizationUrl, String receiptRef) {}

    record Grant(
            String externalAccountRef,
            String credentialRef,
            Set<String> grantedScopes,
            Instant authorizedAt,
            Instant expiresAt,
            String receiptRef) {}

    record Revocation(boolean confirmed, String receiptRef) {}
}
