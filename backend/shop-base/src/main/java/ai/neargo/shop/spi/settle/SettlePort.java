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
/**
     * 记一笔<b>发起支付</b>（{@code stl_payment}，状态 PENDING）。
     *
     * <h2>为什么发起时就要落一行</h2>
     * 不落的话，「用户付了钱而我方没收到回调」这件事<b>在库里没有任何痕迹</b> ——
     * 收款对账轴查的正是「停在 PENDING 的收款」，没有起点就没有可查的对象。
     *
     * <p>2026-09-01 之前这一行不存在：{@code stl_payment} 从 V1 建起就是空表，
     * 于是那条轴每轮都报「没有差异」，而它本该发现的正是掉单。
     *
     * <p><b>幂等</b>：用户在收银台可以反复点「去支付」，同一个 {@code outTradeNo}
     * 重复调只保留第一行 —— 每点一次多一行的话，对账轴会把它们全当成掉单。
     *
     * @param outTradeNo 我方单号，也是回调回来时的关联键
     */
    void openPayment(PaymentOpen cmd);

    /**
     * 一笔收款<b>成功了</b>（{@code stl_payment} 转 SUCCESS）。
     *
     * <h2>它必须发生在订单转 PAID 之前</h2>
     * 支付成功这件事的<b>权威在支付域</b>（它对着通道回执），订单状态是下游投影。
     * 反过来先改订单的话，一旦这一步失败，库里就是
     * <b>「订单说付了、而支付域没有这笔钱」</b> —— 那比反过来严重得多：
     * 后者（钱记了、订单没转）有 I8 每小时兜底，前者没有任何东西能发现。
     *
     * <p><b>幂等</b>：通道会重推，同一个 {@code outTradeNo} 重复调不产生第二行、
     * 也不覆盖已有的成功时刻。
     */
    void settlePayment(PaymentSettled cmd);

    /**
     * @param outTradeNo  我方单号（今天等于订单号）
     * @param orderNo     订单号
     * @param userNo      付款人
     * @param entityNo    收款主体。可为空 —— 多商家的单在这一层还没拆开
     * @param payChannel  通道
     * @param amountMinor 应付金额（分）
     */
    record PaymentOpen(String outTradeNo, String orderNo, String userNo, String entityNo,
                       String payChannel, long amountMinor) {
    }

    /**
     * @param outTradeNo  我方单号
     * @param payChannel  通道
     * @param tradeNo     通道侧交易号。<b>对账时按它去通道查</b>，所以不能为空
     * @param amountMinor 通道回执里的实收金额（分）
     * @param succeededAt 通道回执的成功时刻
     */
    record PaymentSettled(String outTradeNo, String payChannel, String tradeNo,
                          long amountMinor, long succeededAt) {
    }

    int generateForOrder(String orderNo);

    boolean reverseSplit(String subOrderNo);

    /**
     * 发起退款。**调用前必须确认 {@link #reverseSplit} 已成功。**
     *
     * @return 支付服务商的退款单号
     */
    String refund(String subOrderNo, long amountMinor, String reason);
}
