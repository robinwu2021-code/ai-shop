package ai.neargo.shop.pay.settle.refund;

/** 历史收付路由随原始基数保留，退款不能跟随商家后来改绑的账户。 */
public record SettlementAccountSnapshot(String payMerchantNo, String payChannel, String payScene,
                                        String currency, String feeBearer, String wxAppid) { }
