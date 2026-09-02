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
}
