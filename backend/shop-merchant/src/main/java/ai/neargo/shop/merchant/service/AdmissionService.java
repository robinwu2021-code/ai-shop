package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import java.util.List;

/**
 * 准入策略与保证金账户的读写（落地清单 F-6）。
 *
 * <p>与 {@code AdmissionPortImpl} 分工：Port 只回答「让不让」，
 * 这里负责「配什么、缴了多少」。分开是因为前者被商品域与交易域高频调用，
 * 后者只被运营端和商家端调用——把运营写操作混进 Port，
 * 等于让每个下单请求都携带着改保证金的能力。
 */
public interface AdmissionService {

    List<MchAdmissionPolicy> policies();

    /** 按档位更新策略。档位不存在不新建——S 轴已锁定为三档，凭空多出一档只可能是笔误。 */
    void updatePolicy(String legalForm, MchAdmissionPolicy patch, String operator);

    DepositVO deposit(String merchantNo);

    /**
     * 记一笔保证金变动。
     *
     * @param amountMinor 有符号：缴纳为正、扣划为负
     */
    void recordTxn(String merchantNo, String txnType, long amountMinor, String reason, String operator);

    List<TxnVO> txns(String merchantNo);

    /**
     * @param requiredMinor 本档位应缴，端上要用它算「还差多少」——
     *                      只给余额的话商家得自己去别处找标准。
     */
    record DepositVO(String merchantNo, long paidMinor, long frozenMinor, long availableMinor,
                     long requiredMinor, boolean sufficient,
                     long singleOrderLimitMinor, long dailyAmountLimitMinor) {
    }

    /**
     * 设置收款额度上限（分）；{@code 0 = 未设置，不拦}。
     *
     * <p>只设上限，<b>不动已用量</b>：用量是支付累加出来的事实，
     * 让运营能改它等于让人可以把账做平。
     */
    void setPayQuotaLimit(String merchantNo, String storeNo, long quotaLimitMinor, String operator);

    record TxnVO(String txnNo, String txnType, long amountMinor, long balanceAfterMinor,
                 String reason, String operator, String createdAt) {
    }
}
