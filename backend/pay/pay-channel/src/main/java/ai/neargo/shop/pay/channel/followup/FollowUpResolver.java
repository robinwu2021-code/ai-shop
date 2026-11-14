package ai.neargo.shop.pay.channel.followup;

/**
 * 补查查到了结论，交给业务域入账。<b>必须与渠道回调走同一条入账路径</b>（幂等键 = 我方单号）：
 * 补查和回调是同一个真相的两个来源，可能同时到达 —— 另写一段「补一下状态」会重复入账，或者漏掉发券、积分、通知里的某一个。
 */
public interface FollowUpResolver {

    /**
     * 渠道确认已支付。
     *
     * @return false = 业务侧认领不了（流水里没有这个单号、金额对不上）—— 转人工，不再自动重试
     */
    boolean paid(String payChannel, String outTradeNo, String channelTradeNo, long amountMinor, String currency);
}
