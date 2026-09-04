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
    /**
     * @return 实际使用的 {@code out_trade_no} —— <b>调用方要拿它去向通道下单</b>，
     *         而不是用自己传进来的那个。复用未终态的收款时返回已有那笔的单号。
     */
    String openPayment(PaymentOpen cmd);

    /**
     * <b>发起支付：落一行流水 + 向通道下单，一次做完。</b>
     *
     * <h2>为什么合成一个方法，而不是让调用方先 open 再 prepay</h2>
     * 两步之间有一个真实窗口：<b>流水记了而通道没下单</b>。
     * 那笔单会停在 PENDING，被对账轴当成「掉单」去回查，
     * 而通道那边根本没有它 —— 于是每一轮都查一次、每一轮都查不到。
     * 反过来（先下单再记账）更糟：用户手上有能付的凭据而我方一无所知。
     *
     * <p>合成一个之后，顺序与失败处理只有一处，而不是每个调用方各写一遍。
     *
     * <h2>下单失败时流水怎么办</h2>
     * <b>关掉它</b>，不留在 PENDING。留着的话对账会反复回查一笔
     * 通道那边压根不存在的单；而关掉之后用户重试会开一笔新的（带后缀的新单号），
     * 那正是重试该有的样子。
     *
     * @return 成功时带端上唤起收银台的参数；失败时 {@code success = false} 且带原因
     */
    PayInitResult initPayment(PaymentOpen cmd);

    /**
     * @param success     下单成不成功。**失败时端上不要唤起收银台**
     * @param outTradeNo  实际使用的商户单号（重试时带后缀）
     * @param payChannel  实际使用的通道 —— 可能与请求时不同（未指定时由服务端解析）
     * @param payParams   端上唤起收银台要用的参数，<b>原样透传</b>
     * @param message     失败原因，给人看
     */
    record PayInitResult(boolean success, String outTradeNo, String payChannel,
                         java.util.Map<String, String> payParams, String message) {
    }

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
    /**
     * @return 这笔收款关联的<b>订单号</b>。
     *
     * <p><b>为什么由这里返回，而不是让回调自己解析</b>：
     * {@code out_trade_no} 是我方给通道的商户订单号，
     * 2026-09-01 起它<b>独立于订单号生成</b>（同一笔订单支付失败后重试
     * 必须换新号 —— 通道要求商户订单号唯一，而关掉的单不能复用）。
     * 于是「这个通道单号对应哪个订单」只有支付域答得上来。
     *
     * <p>找不到对应流水时返回 {@code null} —— 调用方要把它当成
     * 「这笔钱认领不了」，而不是当成某个订单付成功了。
     */
    String settlePayment(PaymentSettled cmd);

    /**
     * <p><b>这里没有 out_trade_no</b>：商户单号由支付域生成并<b>回传</b>
     * （见 {@link #openPayment} 的返回值）。2026-09-01 之前调用方传一个进来，
     * 而改成支付域自己生成之后那个入参就再没被读过 ——
     * <b>一个被静默丢掉的参数，比没有这个参数危险得多</b>：
     * 调用方会以为自己指定了单号，而实际用的是另一个，两边对不上的时候
     * 没有任何地方会报错。所以直接删掉，让编译器说话。
     *
     * @param orderNo     订单号
     * @param userNo      付款人
     * @param entityNo    收款主体。可为空 —— 多商家的单在这一层还没拆开
     * @param payChannel  通道
     * @param amountMinor 应付金额（分）
     */
    record PaymentOpen(String orderNo, String userNo, String entityNo,
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

    /**
     * <b>退款确认到账</b>：把退款流水推到终态。由退款回调与对账回查共同驱动。
     *
     * <p>入参是<b>退款的商户单号</b>（{@code 原单号-R序号}），不是我方流水号 ——
     * 通道回调里给的就是它（微信 {@code out_refund_no}），
     * 而对账轴回查用的也是它。两处用同一个键，是为了让
     * 「回调先到」与「回查先到」走同一条路。
     *
     * @return 这笔退款挂在哪个订单上；认领不了返回 {@code null}
     *         —— 调用方要当成「这笔退款不是我方发出去的」，而不是当成成功
     */
    String settleRefund(String outRefundNo, String providerNo);
}
