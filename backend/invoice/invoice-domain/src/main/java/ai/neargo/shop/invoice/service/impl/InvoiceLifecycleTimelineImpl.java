package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.service.InvoiceApplicationService;
import ai.neargo.shop.invoice.service.InvoiceDeliveryService;
import ai.neargo.shop.invoice.service.InvoiceLifecycleTimeline;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceTransmissionAuditService;
import ai.neargo.shop.print.api.PrintActor;
import ai.neargo.shop.print.api.PrintQueryService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class InvoiceLifecycleTimelineImpl implements InvoiceLifecycleTimeline {
    private final InvoiceService invoices;
    private final InvoiceApplicationService applications;
    private final InvoiceTransmissionAuditService transmissions;
    private final ObjectProvider<InvoiceDeliveryService> deliveries;
    private final ObjectProvider<PrintQueryService> printing;

    public InvoiceLifecycleTimelineImpl(InvoiceService invoices, InvoiceApplicationService applications,
            InvoiceTransmissionAuditService transmissions, ObjectProvider<InvoiceDeliveryService> deliveries,
            ObjectProvider<PrintQueryService> printing) {
        this.invoices = invoices;
        this.applications = applications;
        this.transmissions = transmissions;
        this.deliveries = deliveries;
        this.printing = printing;
    }

    @Override
    public PageData<Event> list(Scope scope, String invoiceNo, boolean printRead, boolean printExport, int page, int size) {
        var invoice = invoices.get(scope, invoiceNo);
        var events = new ArrayList<Event>();
        if (invoice.applicationNo() != null) {
            for (var event : applications.events(scope, invoice.applicationNo())) {
                events.add(new Event("APPLICATION", event.action(), event.toStatus(), utc(event.occurredAt()),
                        event.actorNo(), invoice.applicationNo() + "/" + event.revisionNo(), invoiceNo));
            }
        }
        for (var attempt : transmissions.list(scope, invoiceNo)) {
            events.add(new Event("TRANSMISSION", attempt.operation(), attempt.outcome(), utc(attempt.occurredAt()),
                    attempt.actorNo(), attempt.attemptNo(), attempt.transmissionNo()));
        }
        var print = printing.getIfAvailable();
        if (printRead && print != null) {
            var fields = printExport ? Set.of("SOURCE_INVOICE_READ", "biz:print:export") : Set.of("SOURCE_INVOICE_READ");
            var actor = new PrintActor(scope.tenantNo(), scope.entityNo(), scope.storeNo(), "MERCHANT",
                    scope.actorNo(), null, fields);
            for (int p = 1; ; p++) {
                var proof = print.invoiceProofHistory(invoiceNo, p, 100, actor);
                if (p == 1) for (var r : proof.requests()) {
                    events.add(new Event("PRINT", "REQUEST", r.status(), r.createdAt(), null, r.requestNo(), invoiceNo));
                }
                for (var row : proof.jobs().records()) {
                    var j = row.job();
                    events.add(new Event("PRINT", j.jobKind(), j.status(), j.createdAt(), null, j.jobNo(),
                            j.parentJobNo() == null ? j.requestNo() : j.parentJobNo()));
                    if (j.confirmedAt() != null) events.add(new Event("PRINT", "RECEIPT", j.resultEvidence(),
                            j.confirmedAt(), null, row.attemptNo(), j.jobNo()));
                    var a = row.archive();
                    if (a != null && a.readyAt() != null) events.add(new Event("PDF", "ARCHIVED", a.status(),
                            a.readyAt(), null, a.exportNo(), j.jobNo()));
                    if (a != null && a.deletedAt() != null) events.add(new Event("PDF", "FILE_DELETED", a.status(),
                            a.deletedAt(), null, a.exportNo(), j.jobNo()));
                }
                if (proof.jobs().records().isEmpty() || p * 100L >= proof.jobs().total()) break;
            }
        }
        var mail = deliveries.getIfAvailable();
        if (mail != null) {
            for (int p = 1; ; p++) {
                var rows = mail.list(scope, invoiceNo, p, 100);
                for (var row : rows.records()) {
                    events.add(new Event("MAIL", row.parentDeliveryNo() == null ? "QUEUED" : "RESEND",
                            "PENDING", utc(row.createdAt()), row.actorNo(), row.deliveryNo(), row.parentDeliveryNo()));
                    if (row.finishedAt() != null) events.add(new Event("MAIL", "SMTP_RESULT", row.status(),
                            utc(row.finishedAt()), row.actorNo(), row.deliveryNo(), row.exportNo()));
                    if (row.openedAt() != null) events.add(new Event("MAIL", "DOWNLOAD_AUTHORIZED", "VERIFIED",
                            utc(row.openedAt()), null, row.deliveryNo(), row.exportNo()));
                }
                if (rows.records().isEmpty() || p * 100L >= rows.total()) break;
            }
        }
        events.sort(Comparator.comparing(Event::occurredAt).reversed().thenComparing(Event::category)
                .thenComparing(Event::event).thenComparing(Event::refNo, Comparator.nullsLast(Comparator.naturalOrder())));
        return PageData.ofAll(events, page, size);
    }

    private static Instant utc(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC); }
}
