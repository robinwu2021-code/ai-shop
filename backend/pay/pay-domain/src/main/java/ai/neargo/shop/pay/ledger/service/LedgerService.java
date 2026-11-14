package ai.neargo.shop.pay.ledger.service;

import ai.neargo.shop.pay.ledger.LedgerVoucher;

/** 记账（只增不改）。在调用方的支付事务里执行 */
public interface LedgerService {

    /** 入账；同一幂等键已记过返回原凭证 id，不再记 */
    Long post(LedgerVoucher voucher);

    /** 冲正：追加一张方向相反的凭证（{@code REVERSAL}），原凭证不动 */
    Long reverse(Long voucherId, String reason);
}
