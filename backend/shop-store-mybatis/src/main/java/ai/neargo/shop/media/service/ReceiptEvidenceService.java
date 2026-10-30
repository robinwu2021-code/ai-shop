package ai.neargo.shop.media.service;

import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidence;
import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidenceQuery;

/** Media-owner verification of private cashier evidence, without a caller transaction. */
public interface ReceiptEvidenceService {
    /** Verify current owner metadata and real bytes, returning no URL or read credential. */
    ReceiptEvidence verify(ReceiptEvidenceQuery request);
}
