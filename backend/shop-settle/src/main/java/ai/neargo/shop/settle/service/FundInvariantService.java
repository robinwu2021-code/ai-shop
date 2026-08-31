package ai.neargo.shop.settle.service;

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
     * @param scannedPaid    扫了几个已支付子单（I1 的对照量）
     * @param missingBill    其中没有结算单的 —— <b>可以自动补</b>，{@code generateForOrder} 幂等
     * @param repairedBill   实际补出来几张
     * @param scannedBills   扫了几张结算单（I2 的对照量）
     * @param orphanBill     其中对不上已支付子单的 —— <b>只告警，绝不自动删</b>
     * @param oldestMissingAt 最早那笔缺结算单的支付时刻；没有返回 0。
     *                        它回答的是「漏了多久」，而那决定要不要现在叫人
     */
    record ScanResult(int scannedPaid, int missingBill, int repairedBill,
                      int scannedBills, int orphanBill, long oldestMissingAt) {

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
