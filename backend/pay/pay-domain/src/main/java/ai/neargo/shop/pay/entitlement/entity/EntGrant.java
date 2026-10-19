package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Immutable source identity and owner-side balances are kept in the pay transaction. */
@Getter
@Setter
@TableName("ent_grant")
public class EntGrant extends BaseEntity {
    private String grantNo;
    private String batchNo;
    private String sourceKey;
    private String sourceSnapshot;
    private String templateVersionNo;
    private String userNo;
    private String entityNo;
    private String reservationNo;
    private String paidFactRef;
    private String payloadHash;
    private String status;
    private String sourceDecisionNo;
    private String reasonCode;
    private String preparedSnapshot;
    private Integer quantity;
    private LocalDateTime committedAt;
}
