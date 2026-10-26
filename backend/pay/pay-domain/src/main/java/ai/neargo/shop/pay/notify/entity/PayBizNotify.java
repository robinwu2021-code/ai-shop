package ai.neargo.shop.pay.notify.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_biz_notify（db/pay V5 · 写入方 TDD-支付服务-I5接入层 S6）：支付 → 业务方的一次答复（四向记录之④）。
 * 与支付结果同事务落行，投递在事务外；{@code (target_type, pay_app_id, event_key)} 唯一，同一事件只答复一次。
 */
@Getter
@Setter
@TableName("pay_biz_notify")
public class PayBizNotify extends PayBaseEntity {

    private String traceId;
    /** PAYMENT_SUCCEEDED / PAYMENT_FAILED / PAYMENT_CLOSED / REFUND_SUCCEEDED / REFUND_FAILED / REFUND_REVIEW_REJECTED */
    private String eventType;
    private String eventKey;
    private Integer stateVersion;
    /** INTERNAL_EVENT / WEBHOOK / TERMINAL */
    private String targetType;
    private Long payAppId;
    private String targetUrl;
    private Long payOrderId;
    private Long payRefundId;
    private Long notifyAmountMinor;
    private String notifyCurrency;
    private String payloadMasked;
    /** PENDING / DELIVERED / FAILED / GIVEN_UP */
    private String status;
    private Integer attempts;
    private LocalDateTime nextAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime givenUpAt;
}
