package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prn_scene_policy")
public class PrnScenePolicy extends PrnRow {
    @TableField(value = "policy_no", updateStrategy = FieldStrategy.ALWAYS)
    private String policyNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "scene", updateStrategy = FieldStrategy.ALWAYS)
    private String scene;

    @TableField(value = "current_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String currentVersionNo;

    @TableField(value = "enabled", updateStrategy = FieldStrategy.ALWAYS)
    private Integer enabled;

    @TableField(value = "critical", updateStrategy = FieldStrategy.ALWAYS)
    private Integer critical;

    @TableField(value = "trigger_types", updateStrategy = FieldStrategy.ALWAYS)
    private String triggerTypes;

    @TableField(value = "fallback_printer_no", updateStrategy = FieldStrategy.ALWAYS)
    private String fallbackPrinterNo;

    @TableField(value = "submit_timeout_seconds", updateStrategy = FieldStrategy.ALWAYS)
    private Integer submitTimeoutSeconds;

    @TableField(value = "result_timeout_seconds", updateStrategy = FieldStrategy.ALWAYS)
    private Integer resultTimeoutSeconds;

    @TableField(value = "max_retry_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxRetryCount;

    @TableField(value = "backlog_alert_seconds", updateStrategy = FieldStrategy.ALWAYS)
    private Integer backlogAlertSeconds;

    @TableField(value = "stale_after_seconds", updateStrategy = FieldStrategy.ALWAYS)
    private Integer staleAfterSeconds;

    @TableField(value = "notification_policy", updateStrategy = FieldStrategy.ALWAYS)
    private String notificationPolicy;

    @TableField(value = "output_options", updateStrategy = FieldStrategy.ALWAYS)
    private String outputOptions;

    @TableField(value = "policy_snapshot_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String policySnapshotHash;
}
