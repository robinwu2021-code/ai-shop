package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.entity.StlPayment;

/**
 * 退款结果唯一入口（代码清单 §2.2）：退款单终态、支付单已退 / 占用、退款凭证。由执行器在退款行进入终态的同一事务里调；
 * 没挂退款单的行（存量）什么都不做。只在行第一次进终态时被调 —— 重推不再进来。
 */
public interface RefundOutcomeService {

    void succeeded(String executionNo);

    void failed(String executionNo, String reason);
}
