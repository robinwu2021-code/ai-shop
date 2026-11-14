package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionInternalApi;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The exact original Core authorization crosses typed internal transport; a network error never
 * becomes local source confirmation.
 */
public final class RemoteExternalCertificateConsumptionPort
        implements ExternalCertificateConsumptionPort {
    private final Supplier<ExternalCertificateConsumptionInternalApi> api;

    public RemoteExternalCertificateConsumptionPort(
            Supplier<ExternalCertificateConsumptionInternalApi> api) {
        this.api = api;
    }

    @Override
    public Authorization authorize(Scope scope, Business business, String preview, String hash) {
        var result =
                validate(
                        scope,
                        null,
                        ServiceCalls.call(
                                ServiceName.PLATFORM,
                                () ->
                                        api.get()
                                                .authorize(
                                                        new ExternalCertificateConsumptionInternalApi
                                                                .Authorize(
                                                                scope, business, preview, hash))));
        require(
                business.equals(result.business())
                        && preview.equals(result.previewRef())
                        && hash.equals(result.previewHash()));
        if ("RESERVED".equals(result.status())) require(result.expiresAt().isAfter(Instant.now()));
        return result;
    }

    @Override
    public Authorization freeze(Scope scope, String authorization) {
        return validate(
                scope,
                authorization,
                ServiceCalls.call(
                        ServiceName.PLATFORM,
                        () ->
                                api.get()
                                        .freeze(
                                                new ExternalCertificateConsumptionInternalApi
                                                        .Reference(scope, authorization))));
    }

    @Override
    public Authorization reconcile(Scope scope, String authorization) {
        return validate(
                scope,
                authorization,
                ServiceCalls.call(
                        ServiceName.PLATFORM,
                        () ->
                                api.get()
                                        .reconcile(
                                                new ExternalCertificateConsumptionInternalApi
                                                        .Reference(scope, authorization))));
    }

    @Override
    public Authorization query(Scope scope, String authorization) {
        return validate(
                scope,
                authorization,
                ServiceCalls.call(
                        ServiceName.PLATFORM,
                        () ->
                                api.get()
                                        .query(
                                                new ExternalCertificateConsumptionInternalApi
                                                        .Reference(scope, authorization))));
    }

    @Override
    public Authorization authorizeReversal(
            Scope scope, String authorization, String originalReversalRef) {
        var result =
                validate(
                        scope,
                        null,
                        ServiceCalls.call(
                                ServiceName.PLATFORM,
                                () ->
                                        api.get()
                                                .authorizeReversal(
                                                        new ExternalCertificateConsumptionInternalApi
                                                                .Reversal(
                                                                scope,
                                                                authorization,
                                                                originalReversalRef))));
        require(
                "REVERSE".equals(result.purpose())
                        && authorization.equals(result.originalAuthorizationRef()));
        return result;
    }

    @Override
    public ReversalReadiness reversalReadiness(
            Scope scope, String authorization, String originalReversalRef) {
        var result =
                ServiceCalls.call(
                        ServiceName.PLATFORM,
                        () ->
                                api.get()
                                        .reversalReadiness(
                                                new ExternalCertificateConsumptionInternalApi
                                                        .Reversal(
                                                        scope,
                                                        authorization,
                                                        originalReversalRef)));
        require(
                result != null
                        && Set.of("READY", "WAITING_SOURCE", "UNSUPPORTED")
                                .contains(result.status()));
        if ("READY".equals(result.status())) {
            var actual = validate(scope, null, result.authorization());
            require(
                    "REVERSE".equals(actual.purpose())
                            && authorization.equals(actual.originalAuthorizationRef())
                            && result.reasonCode() == null);
        } else
            require(
                    result.authorization() == null
                            && result.reasonCode() != null
                            && result.reasonCode().matches("[A-Z_]{1,64}"));
        return result;
    }

    private Authorization validate(Scope scope, String reference, Authorization value) {
        require(
                value != null
                        && scope.equals(value.scope())
                        && value.authorizationRef() != null
                        && (reference == null || reference.equals(value.authorizationRef()))
                        && value.business() != null
                        && value.business().orderItemId() > 0
                        && value.business().subOrderNo() != null
                        && value.sourceHash() != null
                        && value.sourceHash().matches("[a-f0-9]{64}")
                        && value.certificateIdentityHash() != null
                        && value.certificateIdentityHash().matches("[a-f0-9]{64}")
                        && value.quantity() != null
                        && value.quantity().signum() > 0
                        && value.quantity().stripTrailingZeros().scale() <= 6
                        && value.unit() != null
                        && value.expiresAt() != null
                        && Set.of("USE", "REVERSE").contains(value.purpose())
                        && ("REVERSE".equals(value.purpose())
                                == (value.originalAuthorizationRef() != null))
                        && Set.of("RESERVED", "PENDING", "CONFIRMED", "REJECTED", "REVERSED")
                                .contains(value.status()));
        if (Set.of("CONFIRMED", "REJECTED", "REVERSED").contains(value.status()))
            require(value.decisionRef() != null && !value.decisionRef().isBlank());
        return value;
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException(
                    "Invalid original external certificate consumption receipt");
    }
}
