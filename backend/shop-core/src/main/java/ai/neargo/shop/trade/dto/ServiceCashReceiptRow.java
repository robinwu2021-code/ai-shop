package ai.neargo.shop.trade.dto;

/** A settled counter cash declaration linked to the original merchant order. */
public record ServiceCashReceiptRow(long id, String orderNo, String subOrderNo, String declarationNo,
                                    String currency, long amountMinor, long paidAt, String status) {}
