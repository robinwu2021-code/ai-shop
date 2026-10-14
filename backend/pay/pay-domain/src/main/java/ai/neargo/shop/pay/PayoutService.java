package ai.neargo.shop.pay;

import java.util.Collection;
import java.util.List;

/**
 * 放款（TDD-账期推进与放款记录 批 2）。<b>这是批次链与应付链接起来的那一段。</b>
 *
 * <p>此前两条线互不相接：批次推到 RECONCILED 之后没有任何东西从它产生一笔付款，
 * 而付款挂在逐张结算单上。这里把「放一批」变成「生成几笔放款记录」，
 * 之后导清单、回填凭证、对银行流水都挂在放款记录上。
 *
 * <p><b>放款仍由运营触发</b>（ADR-011 §5「不自动划转」）。
 */
public interface PayoutService {

    /**
     * ⑤ 真正的放款：{@code RECONCILED} 的批次 → {@code RELEASED}，按收款号分组各生成一笔放款记录，
     * 本批自营结算单置 {@code CONFIRMED}。
     *
     * <p><b>三道闸在这里</b>，任一不过批次不动：
     * 批次不是 RECONCILED → {@code BATCH_NOT_RELEASABLE}；
     * 任一自营单进项票未了结 → {@code PAYOUT_INVOICE_PENDING}（带结算单号清单）；
     * 没有生效收款账户 → {@code PAYOUT_ACCOUNT_MISSING}。
     *
     * <p>第三方单（走分账）的批次不生成放款记录：置 RELEASED 之后由 executeSplit 接（不在本 TDD）。
     *
     * @return 本批生成的放款记录（第三方批次为空）
     */
    List<PayoutVO> releaseBatch(String batchNo, String operator);

    /** @param status 空 = 全部；{@code PENDING} 是待导出队列 */
    List<PayoutVO> list(String status, String entityNo);

    /** 导出进付款清单：PENDING → EXPORTED。幂等 */
    int markExported(Collection<String> payoutNos, String operator);

    /**
     * 登记凭证：PENDING/EXPORTED → PAID，本笔下所有结算单跟着 PAID 并带上同一个凭证号。
     * 凭证号必填。
     */
    PayoutVO markPaid(String payoutNo, String paymentRef, String operator);

    /**
     * 打款失败或退回：→ FAILED。<b>本笔结算单回到待对账、批次回到可放款</b>，
     * 原因必填 —— 退回的钱要能再放一次，而不是留一笔永远「已付」的空账。
     */
    PayoutVO markFailed(String payoutNo, String reason, String operator);

    record PayoutVO(String payoutNo, String batchNo, String entityNo, String payMerchantNo,
                    String accountName, String bankName, String bankBranch, String accountNoMasked,
                    long amountMinor, int billCount, String currency, String status, String channel,
                    String paymentRef, String bankFlowNo,
                    Long exportedAt, Long paidAt, String paidBy, Long matchedAt, String failReason,
                    List<String> settleNos) {
    }
}
