package ai.neargo.shop.pay.migration.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ent_legacy_coupon_operation")
public class EntLegacyCouponOperation extends BaseEntity {
    private String operationNo;
    private String checkoutNo;
    private String originSystem;
    private String originNo;
    private String instanceNo;
    private String userNo;
    private String entityNo;
    private String currency;
    private String ownerBatchNo;
    private String ownerPlanHash;
    private String economicHash;
    private String commandHash;
    private Integer sourceVersion;
    private String sourceSnapshot;
    private String contextSnapshot;
    private String status;
    private Integer everHeld;
    private Long discountMinor;
    private String allocationSnapshot;
    private String ruleSnapshot;
    private Long holdAssetSeq;
    private Long commitAssetSeq;
    private Long releaseAssetSeq;
    private Long reverseAssetSeq;
    private String commitDecisionNo;
    private String abortDecisionNo;
    private String reverseDecisionNo;
    private String sourceOrderNo;
    private String commitSnapshot;
    private String abortSnapshot;
    private String reverseSnapshot;
    private LocalDateTime heldAt;
    private LocalDateTime committedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime reversedAt;
    private LocalDateTime sourceCommittedAt;
    private LocalDateTime sourceReversedAt;
}
