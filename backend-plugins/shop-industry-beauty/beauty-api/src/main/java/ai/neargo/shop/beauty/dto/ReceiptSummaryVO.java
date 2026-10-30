package ai.neargo.shop.beauty.dto;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record ReceiptSummaryVO(
        String paymentOperationNo,
        String paymentNo,
        String outTradeNo,
        String receiptNo,
        String channel,
        String status,
        long amountMinor,
        String currency) {}
