package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.service.FundInvariantService;
import ai.neargo.shop.pay.service.FundInvariantService.SuccessPayment;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>不变式 I8：支付域说收到了钱的，订单必须是已支付。</b>
 *
 * <h2>它守的是哪一种坏</h2>
 * 违反时用户看到的是 <b>「我付了钱，订单还显示待支付」</b> ——
 * 用户可见、会立刻投诉，而<b>客服在后台看到的也是「未支付」</b>，
 * 没有任何线索说明钱其实已经到了。这类工单最后往往靠人去通道后台翻流水，
 * 一笔一笔对，而那时用户已经等了几个小时。
 *
 * <p>它是「回调进 pay」这条链上的第三层保证：
 * <ol>
 *   <li>回调同步调主应用 —— 正常路径，毫秒级；</li>
 *   <li>调失败落 Outbox 重投 —— 主应用短时不可用时兜住；</li>
 *   <li><b>这一层</b> —— 前两层<b>都</b>失效时兜住，包括 Outbox 那一行
 *       自己就没写成功的情况。</li>
 * </ol>
 * 前两层看的是「消息有没有送到」，这一层<b>不看消息，只看事实</b>：
 * 支付域有这笔账，订单是什么状态。
 *
 * <h2>为什么跑在主应用侧</h2>
 * 因为只有这边查得到订单。方向是<b>主应用主动去拉支付域的账</b>，
 * 而不是支付域来推 —— 后者会让收款这条链依赖主应用可用，
 * 而「回调直接进 pay」的初衷恰恰是不要这个依赖。
 *
 * <h2>⚠️ 今天它在生产上扫不到东西 —— 前提还没补上</h2>
 * <b>2026-09-01 查明：生产代码里没有一处写 {@code stl_payment}。</b>
 * 那张表从 {@code V1__baseline} 建起就是空的，回调（{@code ChannelPayCallbackController}）
 * 只调 {@code orderService.markPaid}，不落支付流水。
 *
 * <p>连带的不只是 I8：<b>收款对账轴（{@code PaymentReconAxis}）也在对这张空表</b> ——
 * 它委托的 {@code ReconService.scan} 查的是「停在 INIT/PENDING 的收款」，
 * 而一行都没有，于是它每轮都报「没有差异」。
 * 它本该发现的恰恰是「用户付了钱而我方没收到回调」，也就是 I8 要防的同一件事。
 * 四个测试（ReconFlowTest 等）是绿的，因为它们自己造数据 ——
 * <b>逻辑被验证过，而真实链路根本不产生这种数据。</b>
 *
 * <p>所以这一层今天的表现是：{@code scanned == 0}，任务日志报
 * 「一笔成功支付都没扫到，这个数不该是 0」。<b>那是对的</b> ——
 * 对照量正是为了让「没有不一致」与「没有在看」分得开。
 * 补上写流水那一步（TDD-支付域-实施方案 §二·五 的第 ③ 步）之后它才真正开始工作。
 *
 * <h2>自动补，因为方向安全</h2>
 * 补的动作是 {@code markPaid}，它本身幂等（订单已是 PAID 就直接返回），
 * 而且<b>只会把订单从「待支付」推到「已支付」</b>。
 * 反方向（订单 PAID 而支付域没有这笔账）<b>刻意不动</b>：
 * 那说明有人把没收到钱的单标成了已付，而自动「改回未支付」会把一个
 * 需要人来查清楚的问题变成一次静默的状态回退 —— 那种情况只报不动。
 */
@Service
public class OrderPaidReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidReconciler.class);

    private final FundInvariantService payInvariants;
    private final SettleSourcePort tradeSource;
    private final OrderRepairPort orderRepair;

    public OrderPaidReconciler(FundInvariantService payInvariants, SettleSourcePort tradeSource,
                               OrderRepairPort orderRepair) {
        this.payInvariants = payInvariants;
        this.tradeSource = tradeSource;
        this.orderRepair = orderRepair;
    }

    /**
     * @param since 只看这个时刻之后成功的支付
     * @param limit 单轮上限
     */
    public Result reconcile(long since, int limit) {
        List<SuccessPayment> paid = payInvariants.successPaymentsSince(since, limit);
        if (paid.isEmpty()) {
            return new Result(0, 0, 0);
        }
        /*
         * **一次问清楚，不要逐笔查。** 一轮可能有几千笔，
         * 逐笔一个 SELECT 的话这个任务自己会变成数据库上的一次小型压测。
         */
        Set<String> notPaid = Set.copyOf(
                tradeSource.notPaidOrders(paid.stream().map(SuccessPayment::orderNo).toList()));

        int repaired = 0;
        int failed = 0;
        for (SuccessPayment p : paid) {
            if (!notPaid.contains(p.orderNo())) {
                continue;
            }
            try {
                /*
                 * 通道与交易号传 null：这一层只负责把状态推到位。
                 * 真实的通道与交易号在 stl_payment 上，那才是权威 ——
                 * 在这里瞎填一个，会让订单上的那两列看起来像来自通道回执。
                 */
                orderRepair.markPaid(p.orderNo(), null, null);
                repaired++;
                log.warn("[I8] 订单 {} 的支付流水 {} 已成功但订单未 PAID，已补", p.orderNo(), p.paymentNo());
            } catch (RuntimeException e) {
                /*
                 * 补不动的不要把整轮拖垮 —— 一个状态机不允许的订单（比如已取消）
                 * 会让它后面所有的都扫不到。而「支付域收到钱、订单却已取消」
                 * 恰恰是这里面最该有人看的一种，把它算进 failed 报出去。
                 */
                failed++;
                log.error("[I8] 订单 {} 补 PAID 失败，流水 {}", p.orderNo(), p.paymentNo(), e);
            }
        }
        return new Result(paid.size(), repaired, failed);
    }

    /**
     * @param scanned  扫了几笔成功支付。**这是对照量** —— 它是 0 的时候
     *                 「没有不一致」这句话不成立，那只是「没有在看」
     * @param repaired 补了几个订单
     * @param failed   补不动的。<b>大于 0 要有人看</b>
     */
    public record Result(int scanned, int repaired, int failed) {
    }
}
