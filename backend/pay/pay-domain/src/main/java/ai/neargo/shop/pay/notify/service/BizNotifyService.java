package ai.neargo.shop.pay.notify.service;

import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import java.time.Instant;

/**
 * 支付 → 业务方的答复（TDD-支付服务-I5接入层 S6；安全规范 §2.3 webhook 出站）。
 *
 * <p>前四个方法在<b>结果所在的支付事务里</b>调，只落 {@code pay_biz_notify} 一行（第三方单、且接入方登记了 webhook 地址时）；
 * 投递在事务外由 {@link #deliverDue} 做：同头同签名规则的 POST，2xx 即送达，否则指数退避，超过放弃时限记 {@code GIVEN_UP}。
 */
public interface BizNotifyService {

    void paymentSucceeded(PayOrder order);

    void paymentClosed(PayOrder order);

    void refundSucceeded(PayOrder order, PayRefund refund);

    void refundFailed(PayOrder order, PayRefund refund);

    /** 投递到期的答复，返回本轮投出的条数。{@code now} 由调用方给（作业用当前时间，测试可拨快） */
    int deliverDue(Instant now, int limit);

    /** 运营手动重推：不论当前状态立即投一次，投递记录记 {@code MANUAL} 与操作人 */
    void redeliver(Long notifyId, String operatorNo);
}
