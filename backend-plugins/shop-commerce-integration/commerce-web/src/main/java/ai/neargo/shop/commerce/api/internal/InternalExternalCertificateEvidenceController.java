package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidenceInternalApi;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidencePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Original source owners may read scoped persisted supplier receipts, never plaintext certificate
 * material.
 */
@RestController
public class InternalExternalCertificateEvidenceController {
    private final String token;
    private final ExternalCertificateEvidencePort owner;

    public InternalExternalCertificateEvidenceController(
            String token, ExternalCertificateEvidencePort owner) {
        this.token = token;
        this.owner = owner;
    }

    @PostMapping(ExternalCertificateEvidenceInternalApi.BASE + "verified")
    public ResponseEntity<?> verified(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateEvidenceInternalApi.Verification request) {
        return InternalServiceResponses.invoke(
                token,
                supplied,
                () -> owner.verified(request.scope(), request.previewRef(), request.previewHash()));
    }

    @PostMapping(ExternalCertificateEvidenceInternalApi.BASE + "decision")
    public ResponseEntity<?> decision(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied,
            @RequestBody ExternalCertificateEvidenceInternalApi.Decision request) {
        return InternalServiceResponses.invoke(
                token, supplied, () -> owner.decision(request.scope(), request.authorizationRef()));
    }
}
