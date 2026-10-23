package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_batch")
public class PrnBatch extends PrnRow {
    @TableField(value = "batch_no", updateStrategy = FieldStrategy.ALWAYS)
    private String batchNo;

    @TableField(value = "request_no", updateStrategy = FieldStrategy.ALWAYS)
    private String requestNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "batch_kind", updateStrategy = FieldStrategy.ALWAYS)
    private String batchKind;

    @TableField(value = "generation_key", updateStrategy = FieldStrategy.ALWAYS)
    private String generationKey;

    @TableField(value = "parent_batch_no", updateStrategy = FieldStrategy.ALWAYS)
    private String parentBatchNo;

    @TableField(value = "route_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String routeVersionNo;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "expected_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer expectedCount;

    @TableField(value = "confirmed_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer confirmedCount;

    @TableField(value = "failed_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer failedCount;

    @TableField(value = "unknown_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer unknownCount;

    @TableField(value = "cancelled_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer cancelledCount;

    @TableField(value = "resolved_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer resolvedCount;

    @TableField(value = "resolved_status", updateStrategy = FieldStrategy.ALWAYS)
    private String resolvedStatus;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(value = "resolved_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resolvedAt;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;
}
