package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import java.time.Instant;

public interface EntitlementAuthorizationService {
    /** Internal core call after it has obtained a trusted pay quote and frozen the order snapshot. */
    void stageTrustedIntent(String tenantNo, String orderNo, SourceRef source, String quoteRef,
                            String selectionDigest, long amountMinor, String currency, Instant quoteExpiresAt);

    AuthorizationView confirm(String tenantNo, String userNo, String orderNo, AuthorizationCommand command);
}
