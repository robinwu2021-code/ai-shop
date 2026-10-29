package ai.neargo.shop.spi.entitlement;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Actual persisted supplier verification/decision evidence; no certificate code crosses this
 * boundary.
 */
public interface ExternalCertificateEvidenceInternalApi {
    String BASE = "/internal/commerce/certificates/";

    record Verification(
            ExternalCertificateConsumptionPort.Scope scope,
            String previewRef,
            String previewHash) {}

    record Decision(ExternalCertificateConsumptionPort.Scope scope, String authorizationRef) {}

    @PostExchange(BASE + "verified")
    ExternalCertificateEvidencePort.Verified verified(@RequestBody Verification request);

    @PostExchange(BASE + "decision")
    ExternalCertificateEvidencePort.Decision decision(@RequestBody Decision request);
}
