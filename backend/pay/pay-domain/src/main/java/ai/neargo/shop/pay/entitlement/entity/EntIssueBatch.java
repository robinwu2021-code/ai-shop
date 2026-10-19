package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Frozen recipients and a durable cursor keep retries on their original grant operations. */
@Getter
@Setter
@TableName("ent_issue_batch")
public class EntIssueBatch extends BaseEntity {
    private String batchNo;
    private String entityNo;
    private String templateVersionNo;
    private String activityRef;
    private String audienceFactRef;
    private String skipReasons;
    private String audienceSnapshot;
    private String recipientResults;
    private String status;
    private String operatorNo;
    private Integer plannedCount;
    private Integer issuedCount;
    private Integer skippedCount;
    private Integer failedCount;
    private Integer pendingCount;
    private Integer cursorOffset;
}
