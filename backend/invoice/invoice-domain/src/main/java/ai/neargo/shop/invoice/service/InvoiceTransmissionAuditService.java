package ai.neargo.shop.invoice.service;

import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.time.LocalDateTime;
import java.util.List;

/** Redacted, store-scoped provider execution history for the invoice timeline. */
public interface InvoiceTransmissionAuditService {

    List<Attempt> list(Scope scope, String invoiceNo);

    record Attempt(String attemptNo, String transmissionNo, Long attemptIndex, String operation, String gatewayCode,
            String channelAccountNo, String actorNo, String outcome, String safeCode, String payloadHash,
            LocalDateTime startedAt, LocalDateTime finishedAt, Long accountRevision) {

        public LocalDateTime occurredAt() {
            return finishedAt == null ? startedAt : finishedAt;
        }
    }
}
