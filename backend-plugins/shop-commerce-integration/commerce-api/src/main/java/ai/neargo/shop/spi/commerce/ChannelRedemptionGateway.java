package ai.neargo.shop.spi.commerce;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * External issuer actions are independent from local entitlement assets and treasury/payment state.
 */
public interface ChannelRedemptionGateway extends CommerceGateway {
    enum Status {
        CONFIRMED,
        REJECTED,
        UNKNOWN
    }

    record Scope(
            String entityNo,
            String storeNo,
            String connectionNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String connectionRevisionNo,
            String authorizationNo,
            String credentialRef) {}

    record Verify(String operationNo, Scope scope, String controlledHandleRef) {}

    record Certificate(
            String certificateIdentityHash,
            ChannelResourceIdentityGateway.Identity productIdentity,
            String externalOrderRef,
            BigDecimal totalQuantity,
            BigDecimal consumedQuantity,
            BigDecimal remainingQuantity,
            String unit,
            Instant expiresAt,
            Instant validUntil,
            String originalReceiptRef) {}

    record Verified(
            String operationNo, String requestHash, Status status, Certificate certificate) {}

    record Action(
            String operationNo,
            Scope scope,
            String previewRef,
            String originalVerificationOperationNo,
            String certificateIdentityHash,
            String sourceAuthorizationRef,
            String sourceHash,
            BigDecimal quantity,
            String unit,
            String originalVerificationReceiptRef,
            String originalRedemptionOperationNo) {}

    record Result(
            String operationNo,
            String requestHash,
            Status status,
            String certificateIdentityHash,
            BigDecimal appliedQuantity,
            BigDecimal cumulativeQuantity,
            String unit,
            String originalReceiptRef) {}

    Verified verifyCertificate(Verify request);

    Result redeemCertificate(Action action);

    Result queryRedemption(Action action);

    Result reverseCertificate(Action action);

    Result queryReversal(Action action);
}
