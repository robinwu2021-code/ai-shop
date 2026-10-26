package ai.neargo.shop.pay.notify.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** pay_biz_notify_attempt（db/pay V5 · 写入方 TDD-支付服务-I5接入层 S6）：答复的每一次投递一行，含运营手动重推 */
@Getter
@Setter
@TableName("pay_biz_notify_attempt")
public class PayBizNotifyAttempt extends PayBaseEntity {

    private Long payBizNotifyId;
    private Integer attempt;
    /** AUTO / MANUAL */
    private String triggerType;
    private String triggeredBy;
    private LocalDateTime sentAt;
    private Integer httpStatus;
    private String responseMasked;
    private Integer elapsedMs;
    /** DELIVERED / REJECTED / TIMEOUT / ERROR */
    private String outcome;
}
