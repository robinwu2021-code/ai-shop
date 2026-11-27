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
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String CANCELLED_BUSINESS_ONLY = "CANCELLED_BUSINESS_ONLY";
    public static final String CONFIRMED_BUSINESS_ONLY = "CONFIRMED_BUSINESS_ONLY";
    public static final String DRAFT_BUSINESS_ONLY = "DRAFT_BUSINESS_ONLY";
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
