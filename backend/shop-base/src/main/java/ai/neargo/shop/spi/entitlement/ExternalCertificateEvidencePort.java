package ai.neargo.shop.spi.entitlement;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Original verified supplier proof is read by its opaque reference; caller-supplied success fields
 * are never authorization.
 */
public interface ExternalCertificateEvidencePort {
    record Verified(
            ExternalCertificateConsumptionPort.Scope scope,
            String previewRef,
            String previewHash,
            String certificateIdentityHash,
            String originalSkuRef,
            String externalOrderRef,
            BigDecimal remainingQuantity,
            String unit,
            Instant validUntil,
            String originalReceiptRef) {}

    record Decision(
            ExternalCertificateConsumptionPort.Scope scope,
            String authorizationRef,
            String certificateIdentityHash,
            String sourceHash,
            BigDecimal quantity,
            String unit,
            String status,
            String originalReceiptRef) {}

    Verified verified(
            ExternalCertificateConsumptionPort.Scope scope, String previewRef, String previewHash);

    Decision decision(ExternalCertificateConsumptionPort.Scope scope, String authorizationRef);
}
