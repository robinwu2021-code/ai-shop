package ai.neargo.shop.inventory.service;

/** Read-only trace from a paid order reservation through physical issue and accepted returns. */
public interface InventoryOrderTraceService {
    InventoryOrderTrace trace(String orderNo);

    record InventoryOrderTrace(String orderNo, java.util.List<Reservation> reservations) { }
    record Reservation(String reservationId, String ownerId, String status, Integer lifecycleVersion,
                       String outboundNo, java.time.LocalDateTime createdAt,
                       java.util.List<Line> lines) { }
    record Line(Integer lineNo, String fulfillmentRef, String itemId, String locationId,
                Integer reservedQty, Integer issuedQty, Integer releasedQty, String outboundNo,
                java.time.LocalDateTime issuedAt, String updatedBy,
                java.util.List<Ledger> issueLedger, java.util.List<ReturnReceipt> returns) { }
    record Ledger(Long id, String docNo, String reasonCode, Integer qtyDelta, Integer balanceBefore,
                  Integer balanceAfter, String operator, String createdBy, java.time.LocalDateTime occurredAt) { }
    record ReturnReceipt(String receiptNo, String afterSaleNo, Integer qty, String quality,
                         String locationId, String inboundNo, String createdBy,
                         java.time.LocalDateTime createdAt) { }
}
