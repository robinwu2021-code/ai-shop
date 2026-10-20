package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Immutable source identity and owner-side balances are kept in the pay transaction. */
@Getter
@Setter
@TableName("ent_instance")
public class EntInstance extends BaseEntity {
    private String instanceNo;
    private String templateVersionNo;
    private String grantNo;
    private String sourceUnitKey;
    private String userNo;
    private String entityNo;
    private String kind;
    private String status;
    private String ruleSnapshot;
    private Integer unitIndex;
    private Integer totalUnits;
    private Integer remainingUnits;
    private Integer reservedUnits;
    private Long assetSeq;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveUntil;
    private LocalDateTime activationAt;
    private LocalDateTime sourcePaidAt;
}
