package ai.neargo.shop.pay.settle;

/** Committed cash instruction freezes both business ownership and the provider recipient. */
public record SplitCommand(String requestNo,String settleNo,String subOrderNo,String merchantNo,String storeNo,
                           String payMerchantNo,String channel,String currency,long amountMinor) { }
