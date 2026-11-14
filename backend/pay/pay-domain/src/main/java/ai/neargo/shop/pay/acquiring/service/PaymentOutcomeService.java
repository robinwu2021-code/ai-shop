package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.acquiring.dto.ChannelOutcome;

/**
 * <b>唯一</b>把支付单推到成功的地方（代码清单 §2.2；{@code SingleOutcomeWriterTest} 守）。
 * 回调、补查、掉单巡检、餐饮刷新都经收款账本 {@code PaymentLedgerService#settle} 进来，在尝试第一次成功的同一事务里调它。
 *
 * <p>支付单这一层是严格的：关单后到账、实收不符、同一张单第二次成功都<b>不进 SUCCEEDED</b>，各建一条待处置异常。
 * 尝试那一层照记事实（钱确实到了），交易域据此给用户看「支付异常、等待处理」。
 */
public interface PaymentOutcomeService {

    /** @return 支付单进入 SUCCEEDED 时为 true；无支付单或转了异常为 false */
    boolean apply(ChannelOutcome outcome);
}
