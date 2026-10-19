package ai.neargo.shop.trade.order.contract;

/** Order surfaces receive only the processing state and reference, without another store's cash details. */
public record PaymentResolutionView(String status, String reason, String reference) { }
