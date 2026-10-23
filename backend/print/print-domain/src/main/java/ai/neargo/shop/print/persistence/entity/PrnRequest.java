package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_request")
public class PrnRequest extends PrnRow {
    @TableField(value = "request_no", updateStrategy = FieldStrategy.ALWAYS)
    private String requestNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "order_no", updateStrategy = FieldStrategy.ALWAYS)
    private String orderNo;

    @TableField(value = "sub_order_no", updateStrategy = FieldStrategy.ALWAYS)
    private String subOrderNo;

    @TableField(value = "biz_type", updateStrategy = FieldStrategy.ALWAYS)
    private String bizType;

    @TableField(value = "biz_no", updateStrategy = FieldStrategy.ALWAYS)
    private String bizNo;

    @TableField(value = "biz_revision", updateStrategy = FieldStrategy.ALWAYS)
    private String bizRevision;

    @TableField(value = "scene", updateStrategy = FieldStrategy.ALWAYS)
    private String scene;

    @TableField(value = "trigger_type", updateStrategy = FieldStrategy.ALWAYS)
    private String triggerType;

    @TableField(value = "source_event_no", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceEventNo;

    @TableField(value = "source_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRef;

    @TableField(value = "trigger_key", updateStrategy = FieldStrategy.ALWAYS)
    private String triggerKey;

    @TableField(value = "source_occurred_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime sourceOccurredAt;

    @TableField(value = "policy_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String policyVersionNo;

    @TableField(value = "policy_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String policySnapshot;

    @TableField(value = "output_options", updateStrategy = FieldStrategy.ALWAYS)
    private String outputOptions;

    @TableField(value = "source_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceSnapshot;

    @TableField(value = "source_snapshot_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceSnapshotHash;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "block_code", updateStrategy = FieldStrategy.ALWAYS)
    private String blockCode;

    @TableField(value = "next_check_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextCheckAt;

    @TableField(value = "deadline_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime deadlineAt;

    @TableField(value = "cancelled_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime cancelledAt;
}
