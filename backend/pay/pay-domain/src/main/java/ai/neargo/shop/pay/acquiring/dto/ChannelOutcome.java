package ai.neargo.shop.pay.acquiring.dto;

/**
 * 渠道确认的一笔收款结果（回调、补查、掉单巡检、刷新共用）。金额币种是<b>渠道说的</b>，不是我方应收。
 *
 * @param stlPaymentId 这笔通道尝试
 */
public record ChannelOutcome(Long stlPaymentId, Long payOrderId, String payChannel, String channelTradeNo,
                             long channelAmountMinor, String channelCurrency, long succeededAt) {
}
