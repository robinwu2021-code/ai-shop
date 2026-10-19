package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Negative source facts outlive in-flight remote requests and must never expire with a UI timeout. */
@Getter
@Setter
@TableName("pay_ent_source_fence")
public class PayEntSourceFence extends BaseEntity {
    private String purpose;
    private String sourceKey;
    private String operationNo;
    private String sourceSnapshot;
    private String userNo;
    private String entityNo;
    private String status;
    private String decisionSnapshot;
    private LocalDateTime cancelledAt;
}
