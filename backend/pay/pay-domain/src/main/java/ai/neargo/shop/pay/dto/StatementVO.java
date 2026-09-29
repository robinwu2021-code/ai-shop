package ai.neargo.shop.pay.dto;

import java.util.List;

/**
 * 商家对账单（按周期）。
 *
 * <p><b>这是凭证，不是报表。</b>小微供应商没有发票、没有对公流水，
 * 平台出的对账单是他唯一能说明「这笔钱是怎么来的」的东西——
 * 无论是自己记账，还是将来升个体户后向税务说明历史收入。
 *
 * <p>所以它必须**可导出留存**，且每一行都能与外部账单勾对。
 *
 * @param voucherNos 凭证号汇总。自营是付款凭证号（网银流水），
 *                   第三方是分账回执号（{@code provider_no}）——
 *                   两者语义不同但作用相同：**与外部账单勾对的锚点**，所以合成一列
 */
public record StatementVO(String period,
                          String merchantNo,
                          String businessMode,
                          long grossMinor,
                          long commissionMinor,
                          long serviceFeeMinor,
                          /**
                           * 代收的运费合计（分）。<b>不在 {@link #grossMinor} 里</b>（§9 AC21）。
                           *
                           * <p>这两列不给出去的话，这张凭证上
                           * {@code gross − 佣金 − 服务费 ≠ net} —— 而它存在的全部理由就是
                           * 「每一行都能与外部账单勾对」。差额无法解释的对账单不是凭证，是麻烦。
                           */
                          long freightIncomeMinor,
                          /** 平台代寄时垫付、从收款里扣回的快递费合计（分）。商家自寄为 0。 */
                          long freightCostMinor,
                          long netMinor,
                          int billCount,
                          List<String> voucherNos,
                          List<Line> lines) {

    /**
     * 一行 = 一张结算单。
     *
     * @param commissionRate 万分比。**必须逐行给**——商家要能自己算清楚差额从哪来，
     *                       只给合计的话，他每次都要来问客服
     * @param voucherNo      该行的凭证号；未结算时为空
     */
    public record Line(String settleNo, String orderNo, String subOrderNo,
                       long grossMinor, long commissionMinor, long serviceFeeMinor,
                       /** 这一单代收的运费（分）。非快递单为 0 */
                       long freightIncomeMinor,
                       /** 这一单被扣的实付快递费（分）。商家自寄为 0 */
                       long freightCostMinor,
                       long netMinor, int commissionRate,
                       String status, String invoiceStatus,
                       Long settledAt, String voucherNo) {
    }
}
