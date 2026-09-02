package ai.neargo.shop.pay.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>测试渠道</b>（{@code TEST}）—— 用来在真通道凭证到位之前把整条链路跑通。
 *
 * <h2>它与 {@code STUB} 的区别，是这个类存在的全部理由</h2>
 * {@code StubPayGateway} 是开发期的「恒成功」假网关：任何调用都返回 ok。
 * 它能证明代码没抛异常，<b>不能证明链路通了</b> ——
 * 因为无论上下游对不对，它都成功。
 *
 * <p>测试渠道不一样，它<b>记住自己发生过什么</b>：
 * <ul>
 *   <li>下过单的商户单号才 {@code found = true}，没下过的返回「通道没有这笔」；</li>
 *   <li>没有回调进来之前 {@code paid = false} —— 对账回查会如实说「还没付」；</li>
 *   <li>金额按下单时记的那个返回，<b>不是回声</b>：
 *       上游传错金额时对账会报「金额不符」，而不是跟着错。</li>
 * </ul>
 * 于是「发起 → 回调 → 流水 → 订单 → 结算单 → 对账 → 提现」这一整条，
 * 每一环都要真的做对才走得通。<b>而这正是接真通道那天最容易出事的地方。</b>
 *
 * <h2>⚠️ 这个类将来要删</h2>
 * 删的时候三件事一起做，缺一件都会留下一条「看起来能用」的假通道：
 * <ol>
 *   <li>删这个类；</li>
 *   <li>删 {@code sys_pay_channel} 里的 {@code TEST} 记录（V288 种的）；</li>
 *   <li>查 {@code mch_payment_merchant} 有没有商家还签着它 —— 有的话先迁走，
 *       否则那些商家的收款会指向一个不存在的通道，
 *       而症状是下单时「通道未接入」。</li>
 * </ol>
 *
 * <h2>为什么不加 {@code @ConditionalOnProperty}</h2>
 * 装配条件今天已经吃过一次亏：一个 app service 挂着
 * {@code embedded} 条件而生产是 {@code standalone}，上线即挂（2026-09-02）。
 * <b>开关放在数据里，不放在装配里</b> ——
 * {@code sys_pay_channel.TEST.enabled} 默认 0，运营开了才出现在渠道列表里。
 * 类装配了但没人能选它，是安全的；类没装配而有人选了它，是启动失败。
 */
@Component
public class TestPayGateway implements PayGateway {

    private static final Logger log = LoggerFactory.getLogger(TestPayGateway.class);

    /** 通道码。与 {@code sys_pay_channel.pay_channel} 一致，V288 种的那条 */
    public static final String CHANNEL = "TEST";

    /**
     * 通道侧的「订单本」。
     *
     * <p>放内存而不是落库：它模拟的是<b>通道那一侧</b>的状态，
     * 而通道的状态本来就不在我们库里。落库反而会让人以为那是我方账 ——
     * 而「我方账」与「通道账」正好是对账要比的两边，混在一起就没得比了。
     *
     * <p>代价是重启即失忆：重启后老单的回查会返回「通道没有这笔」。
     * 联调时这是可接受的，<b>而且它诚实</b> —— 比返回一个编造的「已支付」好。
     */
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    private record Order(long amountMinor, String tradeNo, boolean paid) {
    }

    @Override
    public String payChannel() {
        return CHANNEL;
    }

    /**
     * 向通道下单。<b>记住这笔，供后续回查</b>。
     *
     * <p>返回的参数<b>刻意与微信 JSAPI 同形</b>（prepayId / outTradeNo / amount）——
     * 端上按这套字段渲染，将来换真通道时改的是通道那一侧，不是端。
     *
     * <p>另外带一个 {@code testChannel: "true"}：<b>端上要能一眼看出这是测试通道</b>，
     * 而不是渲染出一个和真收银台一模一样的界面。
     */
    @Override
    public PrepayResult prepay(PrepayCommand cmd) {
        if (cmd.amountMinor() <= 0) {
            // 真通道一定会拒 0 元单，这里也拒 —— 恒成功的桩会让这种错在联调里永不暴露
            return PrepayResult.fail("金额必须大于 0");
        }
        String tradeNo = placeOrder(cmd.outTradeNo(), cmd.amountMinor());
        return PrepayResult.ok(Map.of(
                "prepayId", "test_" + cmd.outTradeNo(),
                "outTradeNo", cmd.outTradeNo(),
                "amount", String.valueOf(cmd.amountMinor()),
                "testChannel", "true"), tradeNo);
    }

    /**
     * 向通道下单。<b>记住这笔，供后续回查</b>。
     *
     * @return 通道侧交易号。回调时要带回来，对账时按它去通道后台核
     */
    public String placeOrder(String outTradeNo, long amountMinor) {
        String tradeNo = "TESTTX-" + outTradeNo;
        orders.put(outTradeNo, new Order(amountMinor, tradeNo, false));
        log.info("[test-channel] 下单 outTradeNo={} amount={} → tradeNo={}",
                outTradeNo, amountMinor, tradeNo);
        return tradeNo;
    }

    /**
     * 模拟「用户付款成功」。联调时由运营端或脚本触发。
     *
     * @return false 表示这笔单通道这边没有 —— <b>如实返回，不假装成功</b>
     */
    public boolean markPaid(String outTradeNo) {
        Order o = orders.get(outTradeNo);
        if (o == null) {
            log.warn("[test-channel] 要标记已付的单 {} 通道这边没有 —— 没下过单就回调，链路有问题",
                    outTradeNo);
            return false;
        }
        orders.put(outTradeNo, new Order(o.amountMinor(), o.tradeNo(), true));
        log.info("[test-channel] 标记已付 outTradeNo={}", outTradeNo);
        return true;
    }

    @Override
    public QueryResult query(String outTradeNo) {
        Order o = orders.get(outTradeNo);
        if (o == null) {
            // **通道没有这笔**：ok=true（查询本身成功）· found=false。
            // 与「查询失败」是两件事 —— 后者绝不能关单，见 ReconFlowTest
            return new QueryResult(true, false, false, 0L, null);
        }
        return new QueryResult(true, o.paid(), true, o.amountMinor(), o.tradeNo());
    }

    @Override
    public Result subsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        log.info("[test-channel] 补差 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("TEST-SUBSIDY-" + requestNo);
    }

    @Override
    public Result subsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        log.info("[test-channel] 补差回退 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("TEST-SUBSIDY-RET-" + requestNo);
    }

    @Override
    public Result split(TxContext ctx, long amountMinor, String requestNo) {
        log.info("[test-channel] 分账 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("TEST-SPLIT-" + requestNo);
    }

    @Override
    public Result splitReverse(TxContext ctx, long amountMinor, String requestNo) {
        log.info("[test-channel] 分账回退 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("TEST-SPLIT-REV-" + requestNo);
    }

    @Override
    public Result refund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        /*
         * 退款要**校验原单存在且已付**。恒成功的话，
         * 「给一笔没收到钱的单退款」这种错在联调里根本不会暴露 ——
         * 而那是真通道一定会拒的。
         */
        Order o = orders.get(ctx.outTradeNo());
        if (o == null) {
            log.warn("[test-channel] 退款失败：原单 {} 通道这边没有", ctx.outTradeNo());
            return Result.fatal("原单不存在");
        }
        if (!o.paid()) {
            log.warn("[test-channel] 退款失败：原单 {} 还没付", ctx.outTradeNo());
            return Result.fatal("原单未支付");
        }
        if (amountMinor > o.amountMinor()) {
            log.warn("[test-channel] 退款失败：{} 超过原单金额 {}", amountMinor, o.amountMinor());
            return Result.fatal("退款金额超过原单");
        }
        log.info("[test-channel] 退款 tx={} amount={} req={} reason={}",
                ctx.tradeNo(), amountMinor, requestNo, reason);
        return Result.ok("TEST-REFUND-" + requestNo);
    }
}
