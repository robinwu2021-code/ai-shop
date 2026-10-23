package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prn_action_log")
public class PrnActionLog extends PrnRow {
    @TableField(value = "log_no", updateStrategy = FieldStrategy.ALWAYS)
    private String logNo;

    @TableField(value = "store_sequence", updateStrategy = FieldStrategy.ALWAYS)
    private Long storeSequence;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "operation_no", updateStrategy = FieldStrategy.ALWAYS)
    private String operationNo;

    @TableField(value = "target_type", updateStrategy = FieldStrategy.ALWAYS)
    private String targetType;

    @TableField(value = "target_no", updateStrategy = FieldStrategy.ALWAYS)
    private String targetNo;

    @TableField(value = "action", updateStrategy = FieldStrategy.ALWAYS)
    private String action;

    @TableField(value = "actor_type", updateStrategy = FieldStrategy.ALWAYS)
    private String actorType;

    @TableField(value = "actor_no", updateStrategy = FieldStrategy.ALWAYS)
    private String actorNo;

    @TableField(value = "before_revision", updateStrategy = FieldStrategy.ALWAYS)
    private Long beforeRevision;

    @TableField(value = "after_revision", updateStrategy = FieldStrategy.ALWAYS)
    private Long afterRevision;

    @TableField(value = "before_status", updateStrategy = FieldStrategy.ALWAYS)
    private String beforeStatus;

    @TableField(value = "after_status", updateStrategy = FieldStrategy.ALWAYS)
    private String afterStatus;

    @TableField(value = "reason", updateStrategy = FieldStrategy.ALWAYS)
    private String reason;

    @TableField(value = "safe_details", updateStrategy = FieldStrategy.ALWAYS)
    private String safeDetails;

    @TableField(value = "trace_no", updateStrategy = FieldStrategy.ALWAYS)
    private String traceNo;
}
