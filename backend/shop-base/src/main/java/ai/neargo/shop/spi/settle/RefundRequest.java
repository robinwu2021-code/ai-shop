package ai.neargo.shop.spi.settle;

/** Frozen cash coordinates let recovery reuse the same instruction instead of opening another refund. */
public record RefundRequest(String executionNo, String afterSaleNo, String subOrderNo, String originPaymentNo,
                            long amountMinor, String currency, String reason) { }
