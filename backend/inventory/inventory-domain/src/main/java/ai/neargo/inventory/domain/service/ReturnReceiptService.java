package ai.neargo.inventory.domain.service;

import java.util.List;

/** Records physical return acceptance independently from the cash-refund execution. */
public interface ReturnReceiptService {
    void receive(String receiptNo, String afterSaleNo, String originalOrderNo, String fulfillmentRef,
                 List<Line> lines, String operator);

    /** COMPLETED only after the durable inventory receipt lines are committed. */
    String status(String receiptNo);

    enum Quality { SALEABLE, UNSALEABLE }

    record Line(String itemId, int qty, Quality quality) { }
}
