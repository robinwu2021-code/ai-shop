package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 商家欠款：Z4 追偿三层里的<b>第二层</b>。
 *
 * <p>第一层是保证金扣划（{@link AdmissionService}，表已有），
 * 第三层是停止放款（批次 {@code BLOCKED}）。三层<b>不跳级</b>：
 * 保证金不足才记欠款，欠款抵扣不掉才停放款。
 *
 * <p>⚠️ <b>今天它还没有生产触发者</b>：{@link #incur} 的调用方是退款追偿（Z4），
 * {@link #offset} 的调用方是批次放行 —— 两者都还没建。这是<b>刻意的顺序</b>，
 * 不是遗漏：欠款账户是它们的前提，先有账户，接上去才是一行调用。
 * 建好之后要回来把这段注释删掉。
 */
public interface DebtService {

    /**
     * 记一笔欠款（退款追不回来）。
     *
     * <p><b>幂等，键是源单号。</b>售后事件会重投，而重投一次就让商家凭空多欠一笔 ——
     * 靠 {@code uk_mch_debt_txn_source} 唯一键保证，不靠应用层判断。
     *
     * @param sourceNo 源单号（售后单号）。<b>必填</b> —— 指不出源头的欠款没法向商家解释
     * @return 记账后的欠款余额；重复调用返回当前余额且不再累加
     */
    long incur(String entityNo, long amountMinor, String sourceType, String sourceNo, String reason);

    /**
     * 从一笔可放款金额里抵扣欠款。
     *
     * <p><b>调用方必须与放款在同一个事务里</b>：分开的话，放了款没扣欠款，
     * 那笔欠款就永远扣不到了 —— 而下一批放款时它还在，看起来像没抵扣过。
     *
     * @param payableMinor 本次可放款金额（分）
     * @return 实际抵扣掉的金额（分）。<b>不会超过 payableMinor，也不会超过欠款余额</b>；
     *         没有欠款时返回 0
     */
    long offset(String entityNo, long payableMinor, String batchNo);

    /**
     * <b>用保证金抵掉一部分欠款。人工触发，不自动。</b>
     *
     * <p>[ADR-022 §3.3] 定的：扣划保证金<b>必须人工</b> —— 动的是商家的本金，
     * 而未经同意从保证金扣款的合规边界还没定（法务待确认）。
     * 所以它不在追偿的自动链路上，是运营在后台按的一个动作。
     *
     * <p>⚠️ 本方案初稿把它写成了追偿的第一层（自动），与 ADR 打架；
     * 2026-08-30 以 ADR 为准改过来了，方案文档已同步。
     * <b>自动的那条链路上一分钱都不动商家的本金。</b>
     *
     * @param operator 谁按的。<b>必填</b> —— 动本金的操作没有操作人就没法追责
     * @return 实际抵掉的金额（分）。不超过欠款余额，也不超过保证金可用余额
     */
    long offsetByDeposit(String entityNo, long amountMinor, String operator,
                         String reason, String requestNo);

    /** 当前欠款余额（分）。没有账户返回 0 */
    long balanceOf(String entityNo);

    /** 欠款流水，倒序。商家端与运营端都读它 —— 商家要看到「为什么欠、扣到哪了」 */
    List<TxnVO> txns(String entityNo);

    /**
     * @param amountMinor        有符号：产生为正、偿还为负
     * @param balanceAfterMinor  变动后余额，用来逐笔回放对账
     */
    record TxnVO(String txnNo, String txnType, long amountMinor, long balanceAfterMinor,
                 String sourceType, String sourceNo, String batchNo, String reason, long at) {
    }
}
