package ai.neargo.shop.pay.service;

import ai.neargo.shop.pay.service.recon.ReconAxis;

import java.util.List;

/**
 * 对账：把「两边对不上」变成一张能处置的清单。
 *
 * <p><b>为什么必须有它</b>：{@code stl_payment.reconciled_at} 的注释从建库起就写着
 * 「用户付了钱而我方没收到回调，只能靠对账发现，没有别的手段」——
 * 而这一列此前没有任何代码写过。那条唯一的发现手段一直是空的。
 *
 * <p>一期只有<b>平台侧自查</b>这一个产出方：扫我方停在 PENDING 的收款流水，
 * 逐笔向通道查单。渠道账单比对要等通道能力（{@code PayGateway} 目前没有账单下载），
 * 所以 {@link #coverage()} 会明说「渠道侧那一整类差异现在看不见」——
 * 页面照它显示提示条，否则「今天没有差异」是句假话。
 */
public interface ReconService {

    /**
     * 自查一轮：扫超时未终态的收款，逐笔查单，能自动收口的当场收口。
     *
     * <p>三种走向，<b>差别全在「能不能安全关单」上</b>：
     * <ul>
     *   <li>通道说已支付 → 走 {@code OrderService.markPaid}（原本的支付成功链路，
     *       幂等键保证不会重复入账），并记一条已自动修复的差异 —— <b>不另写补状态的代码</b>，
     *       那样会漏掉发券、积分、通知里的某一个</li>
     *   <li>通道说没有这笔 → 我方发起失败，可以安全关单</li>
     *   <li><b>查询本身失败</b> → 什么都不做，下一轮再查。
     *       把它当「没有这笔」关单的话，会把一笔已付的单关掉</li>
     * </ul>
     *
     * @param now 判定基准时间（毫秒）。参数化是为了让测试不必真等 15 分钟
     * @return 本轮的处理计数
     */
    ScanResult scan(long now);

    /** 差异列表（运营端）。{@code status} 为空给全部 */
    List<ReconDiffVO> diffs(String status);

    /**
     * 裁决一条差异。
     *
     * @param resolution 处置结论，<b>必填</b> —— 没有结论的「已处理」等于没处理：
     *                   下个月再对账时，没人知道当初为什么放过它
     * @param ignore     true = 忽略（认定不是问题），false = 已处置
     */
    ReconDiffVO decide(String diffNo, boolean ignore, String resolution, String operatorNo);

    /**
     * 本列表现在覆盖到哪些差异类型。
     *
     * <p>端上照它显示提示条。<b>这不是装饰</b>：只有自查的时候，
     * 「渠道扣了钱而我方没有记录」那一类根本不在列表里，
     * 而运营看到的是一个空列表 —— 与「账是平的」长得一模一样。
     */
    Coverage coverage();

    /**
     * @param scanned  本轮扫到的超时流水数
     * @param repaired 查到已支付、已补回支付成功链路的
     * @param closed   通道确认没有这笔、已关单的
     * @param deferred 查询失败、留到下一轮的（<b>不关单</b>）
     */
    record ScanResult(int scanned, int repaired, int closed, int deferred) {
    }

    /**
     * @param channelBillConnected 渠道账单是否已接入。false 时下面那句话要显示给运营
     * @param note                 覆盖范围说明，直接展示
     */
    record Coverage(boolean channelBillConnected, String note) {
    }

    /**
     * 跑<b>所有对账轴</b>并返回每条的结果与覆盖范围。
     *
     * <p>与 {@link #scan} 的分工：那个只跑收款轴（既有定时任务在调，签名不动）；
     * 这个是四条轴的总入口。
     *
     * <p><b>一条轴炸了不影响其余三条</b> —— 对账是发现机制，
     * 让它因为某一条的问题整体停摆，等于同时失去另外三条的发现能力。
     */
    List<AxisReport> scanAllAxes(long now);

    /**
     * @param axis     轴标识，与 {@code stl_recon_diff.axis} 一致
     * @param outcome  扫描结果；这一轴跑失败时为 null
     * @param coverage 覆盖范围说明。**页面必须显示** —— 不说的话「今天没有差异」是句假话
     * @param error    这一轴跑失败的原因；成功时为 null
     */
    record AxisReport(String axis, ReconAxis.ScanOutcome outcome,
                      ReconAxis.Coverage coverage, String error) {
    }

    /**
     * @param diffType  CHANNEL_ONLY / PLATFORM_ONLY / AMOUNT_DIFF
     * @param source    SELF_CHECK / CHANNEL_BILL —— 端上要能分辨这条是怎么来的
     */
    record ReconDiffVO(String diffNo, String billDate, String payChannel, String diffType,
                       String source, String paymentNo, String orderNo, String channelTxnNo,
                       long channelAmountMinor, long platformAmountMinor, String status,
                       String resolution, String recoveredOrderNo, long createdAt,
                       Long resolvedAt, String resolvedBy) {
    }
}
