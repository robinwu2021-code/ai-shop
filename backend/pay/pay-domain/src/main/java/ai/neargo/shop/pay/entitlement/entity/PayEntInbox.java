package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Idempotency ledger for committed cross-domain events applied by pay. */
@Getter
@Setter
@TableName("pay_ent_inbox")
public class PayEntInbox extends BaseEntity {
    private String consumer;
    private String eventNo;
    private String payloadHash;
    private LocalDateTime processedAt;
}
