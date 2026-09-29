package ai.neargo.shop.elec.gateway;

/**
 * 询价有结果了，告诉买家：微信订阅消息（加速通道）+ 站内信（必达的记录）。
 *
 * <p>两样都在主系统（openid、订阅额度、站内信收件箱都是主系统的），独立进程里的实现是
 * HTTP 调 {@code ElecInternal.NOTIFY_QUOTED}。<b>失败不抛</b>：通知送不到不能把报价回滚掉，
 * 调用方据返回值写 buyer_notified_at。
 */
public interface ElecBuyerNotifier {

    /**
     * @param result  QUOTED 已报价 / NO_SOURCE 暂无货源
     * @param summary 料号概述，如「STM32F103C8T6 等 3 项」
     * @return 至少一条通道送到了
     */
    boolean rfqResult(String userNo, String rfqNo, String result, String summary);
}
