package ai.neargo.shop.pay.service;

import ai.neargo.shop.spi.settle.SettlePort;

/**
 * 收款流水（{@code stl_payment}）—— <b>「这笔钱进没进来」的权威记录</b>。
 *
 * <h2>它补的是一个一直空着的位置</h2>
 * 2026-09-01 查明：{@code stl_payment} 从 {@code V1__baseline} 建起，
 * <b>生产代码里没有一处写过它</b>。回调只调 {@code orderService.markPaid}，
 * 订单状态变了，而支付域这边没有任何记录。
 *
 * <p>连带失效的是收款对账轴（{@code PaymentReconAxis}）：
 * 它查「停在 INIT/PENDING 的收款」，一行都没有，于是每轮报「没有差异」——
 * 而它本该发现的正是<b>「用户付了钱而我方没收到回调」</b>。
 * 四个测试是绿的，因为它们自己插数据：逻辑被验证过，
 * 而真实链路根本不产生这种数据。
 *
 * <h2>两个写入点，缺一个都不行</h2>
 * <ul>
 *   <li><b>发起时</b>写 PENDING —— 没有起点，对账轴就没有可查的对象。
 *       只补成功那一半的话，掉单仍然发现不了：掉单的表现恰恰是「停在 PENDING」；</li>
 *   <li><b>回调成功时</b>转 SUCCESS —— I8 比的就是它。</li>
 * </ul>
 *
 * <h2>幂等靠 {@code out_trade_no}</h2>
 * 用户在收银台会反复点「去支付」，通道也会重推回调。
 * 两处都按 {@code out_trade_no} 认单：重复的发起不产生第二行
 * （多一行就多一笔「掉单」），重复的回调不覆盖已有的成功时刻
 * （覆盖了的话对账查到的成功时刻会一直往后跳）。
 */
public interface PaymentLedgerService {

    /**
     * 发起支付，落一行 PENDING。
     *
     * <p><b>幂等的粒度是「这个订单有没有未终态的收款」</b>，不是「这个单号有没有落过」：
     * 用户在收银台点两次「去支付」应当复用同一笔（否则通道那边多出一个未支付单），
     * 而<b>前一笔失败或关闭之后重试，必须换新的 out_trade_no</b> ——
     * 通道要求商户订单号唯一，关掉的号不能复用。
     *
     * @return 实际使用的 {@code out_trade_no}：复用时是已有那笔的，新建时是新生成的。
     *         <b>调用方要拿它去向通道下单</b>，而不是用自己传进来的那个
     */
    String open(SettlePort.PaymentOpen cmd);

    /**
     * 收款成功，转 SUCCESS。
     *
     * <p>找不到对应的 PENDING 行时<b>补一行 SUCCESS</b>，而不是抛错：
     * 存量订单（这个功能上线之前发起的）根本没有起点行，
     * 而它们的回调照样会到 —— 那时抛错等于让通道一直重推一笔永远处理不了的单。
     *
     * @return 这笔钱付的是哪个订单。<b>调用方拿它去推订单状态</b> ——
     *         回调里的商户单号可能带重试后缀（{@code -2}、{@code -3}），
     *         直接拿去查订单会查不到
     */
    String settle(SettlePort.PaymentSettled cmd);

    /**
     * 关掉一笔未终态的收款。
     *
     * <p><b>向通道下单失败时要调它</b>：不关的话那笔流水停在 PENDING，
     * 对账轴会反复回查一笔通道那边压根不存在的单 —— 每轮查一次、每轮查不到，
     * 而「查询失败绝不关单」那条规则会让它永远留在那里。
     *
     * <p>已经终态的直接返回，不报错 —— 关单是幂等的。
     *
     * @param reason 关掉的原因，落进 err_msg 供排查
     */
    void close(String outTradeNo, String reason);

    /**
     * <b>退款：落一行 {@code REFUND} 方向的流水。</b>
     *
     * <h2>这个方法此前不存在，而退款一直在发生</h2>
     * {@code stl_payment} 有五个方向，而生产代码里<b>只有 {@code PAY} 被写过</b>。
     * 退款走的是「退积分 + 回退分账 + 记欠款」三条腿，
     * <b>而资金侧没有任何一行记得「退了这笔钱」</b> ——
     * 于是「这笔退款在资金上真的发生过吗」这个问题没有地方可以问，
     * 对账也扫不到它（对账只看 {@code direction = PAY}）。
     *
     * <h2>挂在原收款上</h2>
     * 退款流水带原收款的订单号与子单号，商户单号是
     * {@code 原单号-R序号} —— <b>退款要能追回是哪一笔钱</b>，
     * 而一笔单可以退多次（退一件、再退一件）。
     *
     * <p><b>幂等按售后单号</b>：同一张售后单重复调只落一行。
     * 重试是常态（分账回退失败会停在 REFUNDING 等续跑），
     * 不幂等的话每重试一次就多一行退款流水，而对账会把它们当成多退。
     *
     * @param afterSaleNo 售后单号 —— 幂等键，也是回查时的线索
     * @return 退款流水号；原收款找不到时返回 {@code null}
     */
    String refund(String orderNo, String subOrderNo, String afterSaleNo,
                  long amountMinor, String reason);

    /**
     * 一笔退款要发给通道时的坐标。
     *
     * <h2>为什么是一个单独的读，而不是让 {@link #refund} 直接返回</h2>
     * 落账与发通道是**两件事，且落账必须先完成**：先发后落的话，
     * 两者之间进程挂掉，钱退出去了而我方没有任何记录 ——
     * 那笔退款既不在对账轴的视野里（轴只扫 {@code stl_payment}），
     * 也没人知道要去追。所以落账拿到单号，再用单号取坐标去发。
     *
     * <p>{@code outRefundNo} 是<b>退款流水自己的 {@code out_trade_no}</b>
     * （形如 {@code 原单号-R1}）。它必须与对账轴回查时用的是同一个值 ——
     * {@code ReconServiceImpl} 拿 {@code p.getOutTradeNo()} 去调
     * {@code queryRefund}，发的时候换个号就永远查不到，
     * 而查不到会被当成「通道没有这笔」。
     *
     * @return 找不到（单号不存在、或不是 REFUND 方向）时 empty
     */
    java.util.Optional<RefundTicket> refundTicket(String refundPaymentNo);

    /**
     * @param payChannel        原收款走的通道
     * @param originTradeNo     原收款的<b>通道交易号</b>。退款优先按它认单
     * @param originOutTradeNo  原收款的商户单号，{@code originTradeNo} 为空时的兜底
     * @param outRefundNo       本次退款的商户单号，见上
     * @param originTotalMinor  原订单总额（分）—— 通道要它来校验部分退款
     * @param amountMinor       本次退款金额（分）
     * @param reason            退款原因，展示给用户
     */
    record RefundTicket(String payChannel, String originTradeNo, String originOutTradeNo,
                        String outRefundNo, long originTotalMinor, long amountMinor,
                        String reason) {
    }

    /**
     * 把「通道怎么答的」回填到退款流水上。
     *
     * <h2>受理不等于退款成功，所以受理时<b>仍留 PENDING</b></h2>
     * 微信退款是异步的：{@code /v3/refund/domestic/refunds} 返回
     * {@code status=PROCESSING} 是常态。这时把行改成 SUCCESS，
     * 账上就写着「退了」而钱还在路上 —— 通道最终拒单的话，
     * 这个差异<b>只有用户来投诉才会被发现</b>。
     * 留 PENDING 则由对账轴回查确认（{@code ReconServiceImpl} 已经在做）。
     *
     * <h2>三种结局，三种处理，区别是有意的</h2>
     * <ul>
     *   <li><b>受理</b>：留 PENDING，记下通道退款单号，等轴确认；</li>
     *   <li><b>不可重试的拒绝</b>（参数错、原单不存在、余额不足）：转 FAILED。
     *       <b>留 PENDING 更糟</b> —— 轴回查会得到「通道没有这笔」，
     *       而那正是它用来安全关单的判据，一笔该退的钱会被静默关掉；</li>
     *   <li><b>可重试的失败</b>（超时、限流）：<b>留 PENDING</b>。
     *       超时的时候「到底发出去没有」是真的不知道，
     *       转 FAILED 等于替通道回答了一个我方答不了的问题。</li>
     * </ul>
     *
     * @param accepted   通道是否受理
     * @param retryable  未受理时，这次失败值不值得重试。{@code accepted} 为 true 时无意义
     * @param providerNo 通道退款单号（微信 {@code refund_id}）。受理时必填
     */
    void markRefundSent(String refundPaymentNo, boolean accepted, boolean retryable,
                        String providerNo, String reason);
}
