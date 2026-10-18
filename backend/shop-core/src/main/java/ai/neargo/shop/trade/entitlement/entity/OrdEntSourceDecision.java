package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ord_ent_source_decision")
public class OrdEntSourceDecision extends BaseEntity {
    private String decisionNo;
    private String purpose;
    private String sourceKey;
    private String sourceSnapshot;
    private String settledSourceKey;
    private Long sourceRevision;
    private String decisionType;
    private String status;
    private String authorizationRef;
    private String paidFactRef;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String currency;
    private Long amountMinor;
    private String snapshotHash;
    private LocalDateTime occurredAt;
}
