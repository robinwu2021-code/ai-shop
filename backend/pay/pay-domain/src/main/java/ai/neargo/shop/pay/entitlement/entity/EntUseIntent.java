package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Pay-owned durable use fact; never written through a core transaction. */
@Getter
@Setter
@TableName("ent_use_intent")
public class EntUseIntent extends BaseEntity {
    private String operationNo;
    private String sourceKey;
    private String activeSourceKey;
    private String sourceSnapshot;
    private String settledSourceKey;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String assetKind;
    private String authorizationRef;
    private String commandSnapshot;
    private String payloadHash;
    private String status;
    private LocalDate serviceDay;
    private String sourceDecisionNo;
    private LocalDateTime expiresAt;
    private LocalDateTime committedAt;
    private String usageNo;
}
