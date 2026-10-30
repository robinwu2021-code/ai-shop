package ai.neargo.shop.svc.client;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidenceInternalApi;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidencePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Explicit COMMERCE reads return original persisted evidence and never manufacture local
 * entitlement readiness.
 */
public final class RemoteExternalCertificateEvidencePort
        implements ExternalCertificateEvidencePort {
    private final Supplier<ExternalCertificateEvidenceInternalApi> api;

    public RemoteExternalCertificateEvidencePort(
            Supplier<ExternalCertificateEvidenceInternalApi> api) {
        this.api = api;
    }

    @Override
    public Verified verified(
            ExternalCertificateConsumptionPort.Scope scope, String preview, String hash) {
        var value =
                ServiceCalls.call(
                        ServiceName.COMMERCE,
                        () ->
                                api.get()
                                        .verified(
                                                new ExternalCertificateEvidenceInternalApi
                                                        .Verification(scope, preview, hash)));
        require(
                value != null
                        && scope.equals(value.scope())
                        && preview.equals(value.previewRef())
                        && hash.equals(value.previewHash())
                        && value.validUntil() != null
                        && value.validUntil().isAfter(Instant.now())
                        && value.remainingQuantity() != null
                        && value.remainingQuantity().signum() > 0
                        && value.unit() != null
                        && value.originalSkuRef() != null
                        && value.originalReceiptRef() != null
                        && value.certificateIdentityHash() != null
                        && value.certificateIdentityHash().matches("[a-f0-9]{64}"));
        return value;
    }

    @Override
    public Decision decision(ExternalCertificateConsumptionPort.Scope scope, String authorization) {
        var value =
                ServiceCalls.call(
                        ServiceName.COMMERCE,
                        () ->
                                api.get()
                                        .decision(
                                                new ExternalCertificateEvidenceInternalApi.Decision(
                                                        scope, authorization)));
        require(
                value != null
                        && scope.equals(value.scope())
                        && authorization.equals(value.authorizationRef())
                        && Set.of("CONFIRMED", "REJECTED", "UNKNOWN").contains(value.status())
                        && value.quantity() != null
                        && value.quantity().signum() > 0
                        && value.unit() != null
                        && value.sourceHash() != null
                        && value.sourceHash().matches("[a-f0-9]{64}"));
        if (!"UNKNOWN".equals(value.status()))
            require(value.originalReceiptRef() != null && !value.originalReceiptRef().isBlank());
        return value;
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException("Invalid original external certificate evidence");
    }
}
