package ai.neargo.shop.media.port;

import ai.neargo.shop.media.service.ReceiptEvidenceService;
import ai.neargo.shop.spi.media.ReceiptEvidencePort;

import org.springframework.stereotype.Component;

/** One real media-owner adapter; no fallback evidence or success implementation. */
@Component
public class ReceiptEvidencePortImpl implements ReceiptEvidencePort {
    private final ReceiptEvidenceService service;

    /** Delegate private ownership and byte checks to the media owner. */
    public ReceiptEvidencePortImpl(ReceiptEvidenceService service) {
        this.service = service;
    }

    @Override
    public ReceiptEvidence verify(ReceiptEvidenceQuery request) {
        return service.verify(request);
    }
}
