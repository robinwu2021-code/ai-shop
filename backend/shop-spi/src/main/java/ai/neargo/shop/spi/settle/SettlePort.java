package ai.neargo.shop.spi.settle;

/**
 * trade → settle：退款链路要用的两步。**顺序不可颠倒**（E4 / TDD-backend §7.2）。
 *
 * <p>已分账的订单退款，必须**先把分账收回来，再把钱退给用户**。
 * 反过来做的话，钱退了但分账收不回 —— 商家已经提现的部分只能人工追，
 * 这是实打实的损失，且没有任何技术手段能补救。
 *
 * <p>因此这个 Port 刻意**不提供「一步退款」的方法**：调用方拿不到那个捷径。
 */
public interface SettlePort {

    /**
     * 回退该子单的分账。
     *
     * @return true=已回退（或本来就没分账）；false=回退失败，**此时绝不能继续退款**
     */
    /**
     * 支付成功后生成结算单（按子单）。**幂等**。
     *
     * <p>刻意做成**同步、同事务**而不是走事件异步：异步投递一旦失败，
     * 就会出现「订单已支付但没有结算单」—— 这种不一致只能靠对账发现，
     * 而发现时钱已经在平台账上躺了几天。结算单只是一行记录，
     * 让它跟支付状态同生共死，比事后补偿简单得多。
     * （真正需要异步的是**分账指令**，那一步要调外部通道，见 X3。）
     */
    int generateForOrder(String orderNo);

    boolean reverseSplit(String subOrderNo);

    /**
     * 发起退款。**调用前必须确认 {@link #reverseSplit} 已成功。**
     *
     * @return 支付服务商的退款单号
     */
    String refund(String subOrderNo, long amountMinor, String reason);
}
