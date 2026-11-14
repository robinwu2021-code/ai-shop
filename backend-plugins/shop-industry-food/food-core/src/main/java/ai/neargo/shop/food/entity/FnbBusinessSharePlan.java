package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Immutable original-source allocation snapshots with auditable business confirmation; never a
 * money ledger.
 */
@Getter
@Setter
@TableName("fnb_business_share_plan")
public class FnbBusinessSharePlan extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String checkNo;
    private String planNo;
    private String activeCheckNo;
    private String requestKey;
    private String requestHash;
    private String status;
    private String mode;
    private String currency;
    private Long originalAmountMinor;
    private Long effectiveAmountMinor;
    private String sourceHash;
    private String planHash;
    private String sourceSnapshot;
    private String divisionSnapshot;
    private String definitionSnapshot;
    private String reason;
    private String confirmedBy;
    private String cancelledBy;
    private String cancellationReason;
}
