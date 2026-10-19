package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import java.time.Instant;

/** Source-specific validation reuses the existing core authorization row and consumer confirmation protocol. */
public interface EntitlementServiceAuthorizationService {
    boolean supports(String tenantNo, String orderNo);
    void stage(String tenantNo, String no, SourceRef source, String previewRef, String selectionDigest,
               long amountMinor, String currency, Instant expiresAt);
    AuthorizationView confirm(String tenantNo, String userNo, String no, AuthorizationCommand command);
}
