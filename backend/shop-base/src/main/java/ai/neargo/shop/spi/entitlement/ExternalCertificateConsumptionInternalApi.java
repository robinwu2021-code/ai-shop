package ai.neargo.shop.spi.entitlement;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Authenticated internal access to the genuine Core completed-business consumption owner. */
public interface ExternalCertificateConsumptionInternalApi {
    String BASE = "/internal/core/external-certificates/";

    record Authorize(
            ExternalCertificateConsumptionPort.Scope scope,
            ExternalCertificateConsumptionPort.Business business,
            String previewRef,
            String previewHash) {}

    record Reversal(
            ExternalCertificateConsumptionPort.Scope scope,
            String authorizationRef,
            String originalReversalRef) {}

    record Reference(ExternalCertificateConsumptionPort.Scope scope, String authorizationRef) {}

    @PostExchange(BASE + "authorize")
    ExternalCertificateConsumptionPort.Authorization authorize(@RequestBody Authorize request);

    @PostExchange(BASE + "freeze")
    ExternalCertificateConsumptionPort.Authorization freeze(@RequestBody Reference request);

    @PostExchange(BASE + "reconcile")
    ExternalCertificateConsumptionPort.Authorization reconcile(@RequestBody Reference request);

    @PostExchange(BASE + "reversal-readiness")
    ExternalCertificateConsumptionPort.ReversalReadiness reversalReadiness(
            @RequestBody Reversal request);

    @PostExchange(BASE + "authorize-reversal")
    ExternalCertificateConsumptionPort.Authorization authorizeReversal(
            @RequestBody Reversal request);

    @PostExchange(BASE + "query")
    ExternalCertificateConsumptionPort.Authorization query(@RequestBody Reference request);
}
