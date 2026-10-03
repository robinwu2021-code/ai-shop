package ai.neargo.shop.pay.service;

import java.util.List;
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

    // ────────────────────────────── pay 这边有哪些账（只读，给跨域巡检用）

    /**
     * 这批子单里<b>已经有结算单</b>的。
     *
     * <p>跨域巡检（I1）拿它与「已支付子单」比：差集就是缺结算单的那些。
     * <b>比对与修复在主应用侧</b>（{@code shop-app/paybridge}），
     * 这里只回答「pay 这边有什么」—— 与 {@link #successPaymentsSince} 同一条理由：
     * 跨域比对天然属于两边之上的那一层，不属于任何一边。
     */
    java.util.Set<String> subOrdersWithBill(java.util.Collection<String> subOrderNos);

    /**
     * 这批子单里<b>已经有发分流水</b>的。跨域巡检（I3）拿它与
     * 「标着已发积分的子单」比：差集就是标记为真而没有流水的那些。
     */
    java.util.Set<String> subOrdersWithEarnLedger(java.util.Collection<String> subOrderNos);

    /**
     * 某段时间内新增的结算单的子单号。跨域巡检（I2）拿它去问 trade
     * 「这些子单付了没」—— 对不上的是孤儿账。
     */
    List<String> billSubOrderNosSince(long since, int limit);


    /**
     * <b>付成功了的那些账</b>（不变式 I8 的左边）。
     *
     * <p>这是支付域对外说的一句话：<b>「我这边收到了这些钱」</b>。
     * 谁拿去跟订单状态比、比出来怎么补，都不是支付域的事 ——
     * I8 的巡检跑在主应用侧，方向是<b>主应用主动来拉</b>。
     *
     * <p>方向刻意不反过来（pay 去查订单、发现没 PAID 就调主应用补）：
     * 那样支付域的巡检就依赖主应用可用，而<b>「回调直接进 pay」的初衷
     * 恰恰是让收款这条链不依赖主应用</b>。一个只读的查询接口是它该露的全部。
     *
     * @param since 只看这个时刻之后成功的。不扫全量 —— 理由同 {@link #scan}
     * @param limit 单轮上限
     */
    List<SuccessPayment> successPaymentsSince(long since, int limit);

    /**
     * @param paymentNo 支付流水号
     * @param orderNo   这笔钱付的是哪个订单。**可能为空** ——
     *                  提现、补贴这类流水没有订单，调用方要自己滤掉
     * @param paidAt    通道回执的成功时刻
     */
    record SuccessPayment(String paymentNo, String orderNo, long paidAt) {
    }

    /**
     * 滞留的积分预占（USE + PENDING 且早于 {@code olderThan}）的子单号。
     *
     * <p>跨域巡检（I6）拿它去问 trade「这些子单还活着吗」，
     * 死掉的那些再调 {@code PointsService.reverse} 释放。
     * <b>「还活着吗」是订单域的问题，所以判断不在这边</b>。
     */
    List<String> pendingHoldSubOrders(long olderThan, int limit);
}
