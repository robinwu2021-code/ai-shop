package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionInternalApi;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only the trusted internal service may ask the original Core owner; native completion and scoped
 * business facts are re-read there.
 */
@RestController
@ConditionalOnProperty(name = "shop.entitlement.external-certificate.enabled", havingValue = "true")
public final class InternalExternalCertificateConsumptionController {
    private final String token;
    private final ExternalCertificateConsumptionPort owner;

    public InternalExternalCertificateConsumptionController(
            @Value("${shop.services.internal-token:}") String token,
            ExternalCertificateConsumptionPort owner) {
        this.token = token;
        this.owner = owner;
    }

    @PostMapping(ExternalCertificateConsumptionInternalApi.BASE + "authorize")
    public ResponseEntity<?> authorize(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateConsumptionInternalApi.Authorize request) {
        return InternalServiceResponses.invoke(
                token,
                supplied,
                () ->
                        owner.authorize(
                                request.scope(),
                                request.business(),
                                request.previewRef(),
                                request.previewHash()));
    }

    @PostMapping(ExternalCertificateConsumptionInternalApi.BASE + "freeze")
    public ResponseEntity<?> freeze(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateConsumptionInternalApi.Reference request) {
        return InternalServiceResponses.invoke(
                token, supplied, () -> owner.freeze(request.scope(), request.authorizationRef()));
    }

    @PostMapping(ExternalCertificateConsumptionInternalApi.BASE + "reconcile")
    public ResponseEntity<?> reconcile(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateConsumptionInternalApi.Reference request) {
        return InternalServiceResponses.invoke(
                token,
                supplied,
                () -> owner.reconcile(request.scope(), request.authorizationRef()));
    }

    @PostMapping(ExternalCertificateConsumptionInternalApi.BASE + "reversal-readiness")
    public ResponseEntity<?> reversalReadiness(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateConsumptionInternalApi.Reversal request) {
        return InternalServiceResponses.invoke(
                token,
                supplied,
                () ->
                        owner.reversalReadiness(
                                request.scope(),
                                request.authorizationRef(),
                                request.originalReversalRef()));
    }

    @PostMapping(ExternalCertificateConsumptionInternalApi.BASE + "authorize-reversal")
    public ResponseEntity<?> authorizeReversal(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateConsumptionInternalApi.Reversal request) {
        return InternalServiceResponses.invoke(
                token,
                supplied,
                () ->
                        owner.authorizeReversal(
                                request.scope(),
                                request.authorizationRef(),
                                request.originalReversalRef()));
    }

    @PostMapping(ExternalCertificateConsumptionInternalApi.BASE + "query")
    public ResponseEntity<?> query(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateConsumptionInternalApi.Reference request) {
        return InternalServiceResponses.invoke(
                token, supplied, () -> owner.query(request.scope(), request.authorizationRef()));
    }
}
