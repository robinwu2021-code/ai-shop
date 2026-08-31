package ai.neargo.shop.pay.dto;

import java.util.List;

/**
 * 提现 / 结算发票 / 个税规则 / 退款回退分账队列的下发形状（矩阵 P-12.2 与 P-12.1.5）。
 *
 * <p><b>时间字段是 ISO-8601 字符串，不是 epoch 毫秒</b>，与 settle 域其余 VO 不同。
 * 理由是这四个类型在 {@code ops-web/lib/types/finance.ts} 与 {@code aftersale.ts} 里
 * 已经声明成 {@code string}，mock 与 {@code lib/mock/db/payout.test.ts} 都按 ISO 串写死。
 * 改成 number 要连带改三处既有绿测，而收益只是「和别的 VO 一致」——
 * <b>契约是规格，形状以它为准</b>（见 TDD-运营端财务补齐 §五 T3）。
 */
public final class FinanceVOs {

    private FinanceVOs() {
    }

    /**
     * 提现单。
     *
     * @param amount           申请金额（分）
     * @param availableBalance 申请<b>那一刻</b>的可提余额（分）。快照，不是实时值 ——
     *                         实时值会因期间的新订单而漂移，而审批看的是申请时的口径
     * @param status           PENDING / APPROVED / REJECTED / PAID / FAILED。
     *                         <b>APPROVED 不等于已打款</b>：打款结果来自渠道回执
     * @param remark           驳回原因 / 大额复核说明。原样回商家 B 端
     */
    public record WithdrawVO(String withdrawNo, String merchantNo, String merchantName,
                             long amount, long availableBalance, String bankAccountMasked,
                             String status, String appliedAt, String decidedAt,
                             String decidedBy, String remark) {
    }

    /**
     * 商家结算发票申请。
     *
     * @param settledAmount 该周期已结算金额（分）。<b>开票金额不得超过它</b> ——
     *                      超出部分没有真实交易对应，就是虚开
     * @param titleType     COMPANY / PERSONAL。企业抬头必须有 {@code taxNo}
     * @param serialNo      开票后回填的发票流水号
     */
    public record SettleInvoiceVO(String invoiceNo, String merchantNo, String merchantName,
                                  String period, long amount, long settledAmount,
                                  String titleType, String title, String taxNo,
                                  String status, String serialNo, String appliedAt,
                                  String decidedAt, String remark) {
    }

    /**
     * 个税代扣规则（P-12.2.3）。只对<b>个人主体</b>生效——个体户与企业自行申报。
     *
     * @param threshold 起征点（分）：单期收入低于它不代扣。
     *                  不设起征点会给每一笔几块钱的提现都产生一条扣税记录
     * @param rate      代扣税率（万分比）。硬上限 4500 —— 再高一定是配置错误
     */
    public record TaxRuleVO(long threshold, long rate, String updatedAt, String updatedBy) {
    }

    /**
     * 待回退分账的售后单（P-12.1.5 / E4）。
     *
     * @param images             举证材料。<b>永远是列表不是 null</b> ——
     *                           运营端契约把它声明成必填数组
     * @param share              赔付出资比例。<b>恒为 null</b>：口径未定（M4），
     *                           {@code ord_after_sale} 上没有存它的列。
     *                           接口不假装它已经判过，运营端会显示「未判定」
     * @param refundSplitPending 恒为 true —— 能出现在这个队列里就意味着待回退。
     *                           下发它是因为运营端按这个字段判断要不要显示执行按钮
     */
    public record RefundSplitBackVO(String afterSaleNo, String subOrderNo, String orderNo,
                                    String merchantNo, String merchantName, String buyerNickname,
                                    String type, String status, long refundMinor, String reason,
                                    List<String> images, String liability, String verdict,
                                    Object share, boolean refundSplitPending, String createdAt) {
    }
}
