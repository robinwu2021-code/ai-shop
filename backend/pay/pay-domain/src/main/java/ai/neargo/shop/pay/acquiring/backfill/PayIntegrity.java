package ai.neargo.shop.pay.acquiring.backfill;

import java.time.LocalDateTime;

/** I3 完成标准的两条校验（开发计划 §5 I3）：上线、回填后各跑一次，都应为 0 */
public interface PayIntegrity {

    /** Q5：{@code since} 之后建的收款尝试里没挂支付单的笔数 */
    long unlinkedAttempts(LocalDateTime since);

    /** Q7：借贷不平（或合计与借方不符）的凭证数 */
    long unbalancedVouchers();
}
