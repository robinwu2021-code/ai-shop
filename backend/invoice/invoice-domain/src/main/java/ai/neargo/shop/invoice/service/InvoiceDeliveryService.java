package ai.neargo.shop.invoice.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.time.LocalDateTime;

public interface InvoiceDeliveryService {
    record Delivery(String deliveryNo, String exportNo, String parentDeliveryNo, String applicationNo,
            Integer applicationRevisionNo, String recipientMask, String status, String providerMessageId,
            String reason, String actorNo, String failureCode, LocalDateTime createdAt, LocalDateTime startedAt,
            LocalDateTime finishedAt, LocalDateTime openedAt) {}
    PageData<Delivery> list(Scope scope, String invoiceNo, int page, int size);
    Delivery enqueue(Scope scope, String invoiceNo, String exportNo, String email, String reason, String key);
    Delivery resend(Scope scope, String invoiceNo, String deliveryNo, String reason, boolean acknowledgeUnknown, String key);
    void challenge(String token, String ip);
    String download(String token, String code, String ip);
    void dispatchDue();
}
