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
                       long netMinor, int commissionRate,
                       String status, String invoiceStatus,
                       Long settledAt, String voucherNo) {
    }
}
