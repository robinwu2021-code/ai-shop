package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Pay-owned durable source event; system outbox is only the delivery adapter. */
@Getter
@Setter
@TableName("pay_ent_outbox")
public class PayEntOutbox extends BaseEntity {
    private String eventNo;
    private String eventType;
    private Integer schemaVersion;
    private String aggregateNo;
    private String payload;
    private String payloadHash;
    private LocalDateTime occurredAt;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
}
