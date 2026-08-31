package ai.neargo.shop.pay.service;

/**
 * 资金不变式巡检 —— <b>第三层保证</b>。
 *
 * <h2>为什么需要第三层</h2>
 * 前两层是 Outbox（保证事件与业务数据同事务落库、至少投递一次）与
 * 幂等消费（保证至多生效一次）。两者合起来是「恰好一次生效」，
 * 覆盖了绝大多数情况 —— 但有两个洞它们盖不住：
 *
 * <ol>
 *   <li><b>Outbox 那一行本身没写成功</b>；</li>
 *   <li><b>消费者的逻辑有 bug</b> —— 消息到了，处理错了。</li>
 * </ol>
 *
 * 这一层<b>不看消息、不看重试次数，只看事实</b>：
 * 「这个子单是已支付的，它有没有结算单？」
 * 它是唯一一层不依赖上面两层正确性的检查，因此不是可选项。
 *
 * <h2>它今天就该有，与拆分无关</h2>
 * {@code SettlePort#generateForOrder} 的注释里写着「刻意同步、同事务」，
 * 理由是异步投递失败会造成「订单已支付但没有结算单」。但<b>同事务并没有
 * 真的消除那个窗口</b>：commit 成功而应用没收到响应、进程在 commit 之后崩溃，
 * 都会留下同样的不一致。单体只是把窗口从秒级缩到毫秒级 ——
 * <b>而窗口没消除，就意味着这件事今天也会发生，只是没有任何东西会发现它。</b>
 */
public interface FundInvariantService {

    /**
     * 扫一轮。
     *
     * @param since 只看这个时刻之后支付的单。**不扫全量** —— 全量扫一次要几分钟，
     *              而这个任务每小时跑一次；漏掉的历史单由更长周期的一轮补
     * @param limit 单轮上限
     */
    ScanResult scan(long since, int limit);

    /**
     * 释放<b>预占了积分、而订单已经不可能成交</b>的那些（不变式 I6）。
     *
     * <h2>为什么它是必需的，而不是「顺手加的兜底」</h2>
     * 下单扣积分是七处跨域调用里<b>唯一的前置动作</b> ——
     * 它必须发生在订单落库之前（要先知道抵扣多少才能算实付）。
     * 支付域独立之后，扣分与建单不再是一个事务，而<b>两个顺序都错</b>：
     *
     * <pre>
     * 扣分 → 建单     扣成功、建失败  →  用户的分白扣了
     * 建单 → 扣分     建成功、扣失败  →  用户白拿了优惠
     * </pre>
     *
     * 前面几处都能靠「推迟到提交之后」解决，这一处不能 ——
     * <b>只能靠补偿</b>。而补偿的两半，仓库里其实已经有了一半：
     * {@code pts_user_ledger.status} 本来就是 PENDING（预占）/ CONFIRMED / REVERSED
     * 三态，注释里写着「预占，此时池子还没付给收单方（订单可能取消）」，
     * {@code reverse} 就是取消那一半，且幂等。
     *
     * <p><b>缺的一直是「没人触发取消」</b>：下单事务回滚时，那条 PENDING 就永远留在那里，
     * 而用户的分已经从 balance 里扣走了。今天扫不出来，也没有任何地方会提起它。
     *
     * <p>顺带堵住另一个口子：取消订单的退分改成「提交后执行」之后，
     * 那一步失败也会留下 PENDING —— 这里一并释放。
     *
     * @param olderThan 只处理这个时刻之前预占的。留出的时间是给正常链路的：
     *                  刚下单的那一瞬间订单可能还没落库，此时释放会把好单的分退掉
     * @param limit     单轮上限
     */
    ReleaseResult releaseDeadHolds(long olderThan, int limit);

    /**
     * @param scanned  扫了几条预占中的流水（对照量）
     * @param dead     其中订单已经不可能成交的
     * @param released 实际退回了几条
     */
    record ReleaseResult(int scanned, int dead, int released) {
    }

    /**
     * @param scannedPaid    扫了几个已支付子单（I1 的对照量）
     * @param missingBill    其中没有结算单的 —— <b>可以自动补</b>，{@code generateForOrder} 幂等
     * @param repairedBill   实际补出来几张
     * @param scannedBills   扫了几张结算单（I2 的对照量）
     * @param orphanBill     其中对不上已支付子单的 —— <b>只告警，绝不自动删</b>
     * @param oldestMissingAt 最早那笔缺结算单的支付时刻；没有返回 0。
     *                        它回答的是「漏了多久」，而那决定要不要现在叫人
     * @param scannedGranted 扫了几个标着「已发过积分」的子单（I3 的对照量）
     * @param grantedNoLedger 其中<b>没有发分流水</b>的 —— 标记说发过而用户一分没拿到，
     *                        且标记本身会挡住重试（{@code grantOnPay} 的幂等就是靠它）
     * @param clearedFlags   把标记改回未发的行数，改完下一轮就能重发
     */
    record ScanResult(int scannedPaid, int missingBill, int repairedBill,
                      int scannedBills, int orphanBill, long oldestMissingAt,
                      int scannedGranted, int grantedNoLedger, int clearedFlags) {

        /**
         * 有没有真的扫到东西。
         *
         * <p><b>「违反 0 条」与「一行都没扫到」在结果上一模一样</b>，
         * 而后者才是最该查的那种（查询条件写错、索引没走上、时间窗算反）。
         * 任何一处报告这个结果的地方都要先看它。
         */
        public boolean scannedAnything() {
            return scannedPaid > 0 || scannedBills > 0;
        }
    }
}
