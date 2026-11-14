package ai.neargo.shop.pay.acquiring.backfill;

import java.time.LocalDateTime;

/** I3 完成标准的两条校验（开发计划 §5 I3）：上线、回填后各跑一次，都应为 0 */
public interface PayIntegrity {

    /** Q5：{@code since} 之后建的收款尝试里没挂支付单的笔数 */
    long unlinkedAttempts(LocalDateTime since);

    /** Q7：借贷不平（或合计与借方不符）的凭证数 */
    long unbalancedVouchers();

    /**
     * 资金恒等式①（W3 E09）：已成功的支付单里「已付 − 已退」与流水净额（收款账户借方 − 贷方）对不上的单号，按 id 升序最多
     * {@code limit} 张。未冻结资金路径的存量单不在内（它们本来就没记流水，等回填 B7）。
     */
    java.util.List<String> ledgerMismatchedOrders(int limit);
}
