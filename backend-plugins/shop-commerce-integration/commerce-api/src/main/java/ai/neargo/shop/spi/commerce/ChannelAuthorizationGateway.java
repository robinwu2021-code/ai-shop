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
