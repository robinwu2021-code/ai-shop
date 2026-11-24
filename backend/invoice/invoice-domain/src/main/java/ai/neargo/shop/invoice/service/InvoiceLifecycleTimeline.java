package ai.neargo.shop.invoice.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.time.Instant;

public interface InvoiceLifecycleTimeline {
    record Event(String category, String event, String status, Instant occurredAt, String actorNo,
            String refNo, String parentRefNo) {}
    PageData<Event> list(Scope scope, String invoiceNo, boolean printRead, boolean printExport, int page, int size);
}
