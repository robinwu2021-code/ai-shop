package ai.neargo.shop.payclient;

import java.util.List;

/**
 * 平台端 · 付款清单导出（ADR-011 · TDD-供应商结算与双轨资金 §3.4）。
 *
 * <p>这是「钱真的能付出去」的最后一环：财务拿着它去网银转账，回来回填流水号。
 *
 * <p><b>为什么返回里要带「没进清单的」</b>：只给能付的那几行，财务看不出
 * 「这一期怎么少了一家」。而少的原因有三种、处理方式完全不同 ——
 * 缺票要去催票、没对账要去确认、没收款账户要去催商家提交并审核。
 * 把原因摆在旁边，比让他去别的页面一家家查便宜得多。
 * 这与 {@code payables-tab} 的「把原因说在前面」是同一条规矩。
 */
public interface OpsPayoutListAppService {

    /**
     * 拉当前可付的清单。
     *
     * <p>进清单的条件<b>三条全中</b>：自营轨、已对账（{@code CONFIRMED}）、
     * 票据已了结（{@code VERIFIED} 或 {@code NO_INVOICE}）。再加一条账户闸：
     * 该主体有生效中的收款账户。
     *
     * @param entityNo 只导某一家，空则全部
     */
    PayoutListVO list(String entityNo);

    /**
     * @param rows       可付的，含<b>明文账号</b>
     * @param blocked    本该付、但缺条件的
     * @param totalMinor {@code rows} 的合计。**不含 blocked** —— 那笔钱这次付不出去，
     *                   算进合计会让财务按一个错的数去备款
     */
    record PayoutListVO(List<PayoutRow> rows, List<BlockedRow> blocked, long totalMinor) {
    }

    /**
     * @param accountNumber <b>明文账号</b>。只在这一次响应里存在 —— 不进日志、不入缓存
     * @param remark        银行附言，形如 {@code 货款-E20260801000001}。
     *                      **回读银行流水时靠它勾对**，所以不能是一句人话
     * @param settleNos     这一行覆盖了哪几张结算单。回填凭证号时按它逐张登记
     */
    record PayoutRow(String entityNo, String merchantName, String accountType,
                     String accountName, String accountNumber,
                     String bankName, String bankBranch,
                     long amountMinor, int billCount, String remark,
                     List<String> settleNos) {
    }

    /**
     * @param reason 为什么没进清单。**给的是可执行的原因**（缺票 / 未对账 / 无收款账户），
     *               不是「不满足条件」这种说了等于没说的话
     */
    record BlockedRow(String entityNo, String merchantName, long amountMinor,
                      int billCount, String reason) {
    }
}
