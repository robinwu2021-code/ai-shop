package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.service.FundInvariantService;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>跨域资金不变式 I1 / I2 / I3</b> —— 拿 pay 的账去比 trade 的单。
 *
 * <h2>为什么在这一层</h2>
 * 跨域比对天然属于两边之上的那一层，<b>不属于任何一边</b>。
 * 此前它跑在支付域里，靠 {@code SettleSourcePort} 反向查订单域（5 个方法）——
 * 而按「除回调外不做反向依赖，pay 只解决 pay 的核心问题」，那条依赖不该存在。
 *
 * <p>搬过来之后 pay 侧只回答「我这边有哪些账」
 * （{@code subOrdersWithBill} / {@code subOrdersWithEarnLedger} /
 * {@code billSubOrderNosSince}），比对与修复都在这里。
 * <b>I8 早就是这个形状</b>（{@link OrderPaidReconciler}），这次是 I1–I3 跟上。
 *
 * <h2>三条的处置方式刻意不同</h2>
 * <ul>
 *   <li><b>I1 自动补</b> —— 补生成结算单是幂等且只增不减的动作，自动执行安全；</li>
 *   <li><b>I2 只告警</b> —— 删账不可逆，而成因不止一种（含「巡检自己算错了窗口」）。
 *       自动删会把几种成因一起变成「账少了几行」，事后谁也说不清是哪一种；</li>
 *   <li><b>I3 清标记</b> —— 标记说发过积分而没有流水时，把标记改回未发。
 *       方向安全：用户一分没拿到，而发放的幂等原本就靠这个标记，
 *       不清掉它永远重发不了。<b>反方向（有流水而标记为假）刻意不动</b> ——
 *       那说明多发了一次，补标记等于把它盖掉，而分还在用户手里。</li>
 * </ul>
 *
 * <p>凡是「自动修复」的，都要能证明它幂等且方向安全。证不了的就只报不动。
 */
@Service
public class FundInvariantReconciler {

    private static final Logger log = LoggerFactory.getLogger(FundInvariantReconciler.class);

    private final FundInvariantService payAccounts;
    private final SettleSourcePort tradeSource;
    private final SettlePort settlePort;
    private final PointsPort pointsPort;

    public FundInvariantReconciler(FundInvariantService payAccounts, SettleSourcePort tradeSource,
                                   SettlePort settlePort,
                                   PointsPort pointsPort) {
        this.payAccounts = payAccounts;
        this.tradeSource = tradeSource;
        this.settlePort = settlePort;
        this.pointsPort = pointsPort;
    }

    public Result scan(long since, int limit) {
        // ── I1：每个已支付子单必有结算单
        List<SettleSourcePort.PaidSubOrder> paid = tradeSource.paidSubOrdersSince(since, limit);
        Set<String> withBill = payAccounts.subOrdersWithBill(
                paid.stream().map(SettleSourcePort.PaidSubOrder::subOrderNo).toList());
        List<SettleSourcePort.PaidSubOrder> missing = paid.stream()
                .filter(p -> !withBill.contains(p.subOrderNo()))
                .toList();

        /*
         * **按主单去重再补。** generateForOrder 是按主单做的：
         * 一个主单缺三个子单的结算单时，调三次和调一次结果相同（它幂等），
         * 但日志里会显示「补了 3 张」而实际只补了一轮 —— 那个数会误导下一个人。
         */
        Set<String> orderNos = new LinkedHashSet<>(
                missing.stream().map(SettleSourcePort.PaidSubOrder::orderNo).toList());
        int repaired = 0;
        for (String orderNo : orderNos) {
            // 逐单独立 try：一个主单补不出来（数据本身有问题）不能把整轮带走
            try {
                repaired += settlePort.generateForOrder(orderNo);
            } catch (RuntimeException e) {
                log.warn("[fund-invariant] I1 补生成失败 orderNo={}：{}", orderNo, e.getMessage());
            }
        }
        long oldestMissingAt = missing.stream()
                .mapToLong(SettleSourcePort.PaidSubOrder::paidAt)
                .filter(x -> x > 0)
                .min().orElse(0L);

        // ── I2：每张结算单必有对应的已支付子单
        List<String> billSubOrders = payAccounts.billSubOrderNosSince(since, limit);
        List<String> orphan = billSubOrders.isEmpty() ? List.of()
                : tradeSource.notPaidAmong(billSubOrders);
        if (!orphan.isEmpty()) {
            log.error("[fund-invariant] **I2 违反 {} 条**：结算单对不上已支付子单，"
                            + "需人工判断（不自动处理）。样本：{}",
                    orphan.size(), orphan.subList(0, Math.min(5, orphan.size())));
        }

        // ── I3：标着「已发过积分」的子单必有发分流水
        List<String> granted = tradeSource.pointsGrantedSince(since, limit);
        Set<String> earned = payAccounts.subOrdersWithEarnLedger(granted);
        List<String> noLedger = granted.stream().filter(no -> !earned.contains(no)).toList();
        int cleared = noLedger.isEmpty() ? 0 : tradeSource.clearPointsGranted(noLedger);
        if (!noLedger.isEmpty()) {
            log.error("[fund-invariant] **I3 违反 {} 条**：标记说发过积分而没有流水，"
                            + "已把标记改回未发（{} 行），下一轮会重发。样本：{}",
                    noLedger.size(), cleared, noLedger.subList(0, Math.min(5, noLedger.size())));
        }

        return new Result(paid.size(), missing.size(), repaired,
                billSubOrders.size(), orphan.size(), oldestMissingAt,
                granted.size(), noLedger.size(), cleared);
    }

    /**
     * <b>I6：释放预占了积分、而订单已经不可能成交的那些。</b>
     *
     * <p>与 I1–I3 分开跑，因为<b>时间窗不同</b>：那三条回看 26 小时，
     * 而预占的积分只需要等几分钟就能判死 —— 订单落库是同一个请求里的事。
     * 用同一个窗口的话，刚回滚的那批要等一整天才还给用户。
     *
     * <p>它同样是跨域比对：pay 说「这些预占还挂着」，trade 说「这些单已经不可能成交」。
     * 判断「还活不活着」是订单域的问题，所以这一步也在这一层。
     */
    public ReleaseResult releaseDeadHolds(long olderThan, int limit) {
        List<String> holds = payAccounts.pendingHoldSubOrders(olderThan, limit);
        if (holds.isEmpty()) {
            return new ReleaseResult(0, 0, 0);
        }
        List<String> dead = tradeSource.subOrdersNotAlive(holds);

        int released = 0;
        for (String subOrderNo : dead) {
            /*
             * 逐条独立 try：一条退不掉不能把整轮带走。
             * reverse 幂等且只认 PENDING —— 与正常取消链路并发跑到同一条上，
             * 先到的那次生效，后到的静默返回。
             */
            try {
                pointsPort.reverse(subOrderNo, "订单未成交，释放预占的积分");
                released++;
            } catch (RuntimeException e) {
                log.warn("[fund-invariant] I6 释放失败 subOrderNo={}：{}", subOrderNo, e.getMessage());
            }
        }
        if (!dead.isEmpty()) {
            log.warn("[fund-invariant] **I6 释放 {} 条预占积分**（扫 {} 条）—— "
                            + "这些单的积分已经扣走而订单不可能成交；持续不为零要查下单链路",
                    released, holds.size());
        }
        return new ReleaseResult(holds.size(), dead.size(), released);
    }

    /**
     * @param scanned  扫了几条滞留的预占（对照量：0 时下面两个数不说明任何问题）
     * @param dead     其中订单已经不可能成交的
     * @param released 真的释放掉的
     */
    public record ReleaseResult(int scanned, int dead, int released) {
    }

    /**
     * @param scannedPaid    扫了几个已支付子单（I1 的对照量）
     * @param scannedBills   扫了几张结算单（I2 的对照量）
     * @param scannedGranted 扫了几个标着「已发过积分」的子单（I3 的对照量）
     *
     * <p>三个 scanned 都要有：<b>「违反 0 条」与「一行都没扫到」在结果上一模一样</b>，
     * 而后者才是最该红的那种（查询条件写错、索引没走上、时间窗算反）。
     */
    public record Result(int scannedPaid, int missingBill, int repairedBill,
                         int scannedBills, int orphanBill, long oldestMissingAt,
                         int scannedGranted, int grantedNoLedger, int clearedFlags) {

        public boolean scannedAnything() {
            return scannedPaid > 0 || scannedBills > 0 || scannedGranted > 0;
        }
    }
}
