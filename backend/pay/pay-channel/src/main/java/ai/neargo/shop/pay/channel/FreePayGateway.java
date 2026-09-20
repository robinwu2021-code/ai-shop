package ai.neargo.shop.pay.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 免支付通道：**应付金额为 0 的单**走这里。
 *
 * <h2>为什么 0 元也要有一笔收款流水</h2>
 * 绕开支付域、直接把订单置为已支付的话，库里会出现
 * <b>「订单说付了、而支付域没有这笔钱」</b> —— 对账轴只扫 {@code stl_payment}，
 * 它看不见这笔单。{@code SettlePort.settlePayment} 的类注释点名这是最严重的
 * 一种不一致：反过来（钱记了、订单没转）有 I8 每小时兜底，这个方向没有任何东西能发现。
 *
 * <h2>它与 STUB / TEST 不是一回事</h2>
 * 那两个是**开发期的假网关**，冒充真通道、可以整体关掉。
 * 这个是**生产的真通道**：0 元本来就不需要向任何外部系统付款，
 * 「不发请求」是它的正确行为，不是妥协。所以它<b>没有开关</b>，恒装配 ——
 * 加开关的话，忘了开的那一格里 0 元单会回到「付不掉」，而那正是本方案要修的缺陷。
 *
 * <h2>为什么不校验金额一定是 0</h2>
 * 校验了也只能抛，而这一层抛出去的错对用户毫无用处。路由是在
 * {@code resolvePayChannel} 决定的（应付为 0 才来这里），
 * 真要防的是**那一处**被改坏 —— 那由 ZeroAmountPayFlowTest 的
 * 「应付 &gt; 0 的单不许走 FREE」守住，比在这里补一句 if 有用。
 */
@Component
public class FreePayGateway implements PayGateway {

    private static final Logger log = LoggerFactory.getLogger(FreePayGateway.class);

    /** 通道名。**定义在 shop-base**，交易域按它路由 —— 两处各写一份字面量的话，
     *  差一个字母的表现是「通道未接入」而不是编译错误 */
    public static final String CHANNEL = ai.neargo.shop.common.PayChannels.FREE;

    @Override
    public String payChannel() {
        return CHANNEL;
    }

    /**
     * 「下单」：不发任何请求，恒成功，<b>参数为空</b>。
     *
     * <p>空参数是有意的：端上拿它唤不起收银台，而它本来就不该唤起。
     * 端上靠 {@code PayResult.settled} 知道这一点，不靠参数是否为空去猜。
     */
    @Override
    public PrepayResult prepay(PrepayCommand cmd) {
        log.info("[free] 0 元单免支付 outTradeNo={} amount={}", cmd.outTradeNo(), cmd.amountMinor());
        return PrepayResult.ok(java.util.Map.of(), CHANNEL + ":" + cmd.outTradeNo());
    }

    /**
     * 对账回查：0 元单<b>恒为已支付</b>，金额 0。
     *
     * <p>返回 {@code notFound} 是不行的 —— 对账轴会把「通道那边没有这笔」
     * 当成异常报出来，而 0 元单在任何外部通道那边本来就不存在。
     * 这里说「有，且已付 0 元」才是真话。
     */
    @Override
    public QueryResult query(String outTradeNo) {
        return QueryResult.paid(0L, CHANNEL + ":" + outTradeNo);
    }

    /*
     * ── 分账 / 补差：0 元单没有资金流可分 ──────────────────────────
     *
     * 四个都走同一条规矩：**只接受 0，非 0 一律拒**。
     *
     * 回 ok 的话，账上会出现一笔「已分账 N 元」而钱一分没动 ——
     * 这条通道压根没有钱可动。而分账对账是按我方台账与通道回执两边核的，
     * 台账上有、通道那边没有，差异要到对账日才冒出来，那时已经积了一堆。
     */

    @Override
    public Result subsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        return zeroOnly("补差", amountMinor, requestNo);
    }

    @Override
    public Result subsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        return zeroOnly("补差回退", amountMinor, requestNo);
    }

    @Override
    public Result split(TxContext ctx, long amountMinor, String requestNo) {
        return zeroOnly("分账", amountMinor, requestNo);
    }

    @Override
    public Result splitReverse(TxContext ctx, long amountMinor, String requestNo) {
        return zeroOnly("分账回退", amountMinor, requestNo);
    }

    /**
     * 0 元通道上的资金动作：金额是 0 就当成功，不是 0 就<b>不可重试地失败</b>。
     *
     * <p>不可重试而不是重试：金额算错了，重试一万次还是那个错的金额。
     * 留在重试队列里只会占着位置，让真正该人工看的单没人看。
     */
    private static Result zeroOnly(String what, long amountMinor, String requestNo) {
        if (amountMinor != 0L) {
            log.error("[free] 0 元单被要求{} {} 分（req={}）—— **上游算错了金额**，"
                    + "这条通道没有钱可动", what, amountMinor, requestNo);
            return Result.fatal("免支付订单的" + what + "只能是 0 元，请求 " + amountMinor + " 分");
        }
        return Result.ok(CHANNEL + "-" + requestNo);
    }

    /**
     * 退款：0 元单只能退 0 元，恒成功且不发任何请求。
     *
     * <h2>要退出非 0 金额时**不许悄悄成功**</h2>
     * 这条通道收进来的钱是 0，能退出去的就只有 0。
     * 请求退 5 元意味着<b>上游算错了退款金额</b>（多半是把商品原价当成了实付），
     * 而这里要是回一个 ok，那 5 元会在账上变成「已退」——
     * 钱没动，账说动了，且没有任何东西会发现。
     *
     * <p>所以回 {@code fatal}（不可重试、要人工看）：重试一万次也还是错的金额。
     */
    @Override
    public Result refund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        if (amountMinor != 0L) {
            log.error("[free] 0 元单被要求退 {} 分（req={}）—— **上游算错了退款金额**，"
                    + "这条通道收进来的就是 0", amountMinor, requestNo);
            return Result.fatal("免支付订单只能退 0 元，请求退 " + amountMinor + " 分");
        }
        log.info("[free] 0 元退款 req={} reason={}", requestNo, reason);
        return Result.ok(CHANNEL + "-REFUND-" + requestNo);
    }
}
