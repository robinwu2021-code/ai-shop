package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_job")
public class PrnJob extends PrnRow {
    @TableField(value = "job_no", updateStrategy = FieldStrategy.ALWAYS)
    private String jobNo;

    @TableField(value = "request_no", updateStrategy = FieldStrategy.ALWAYS)
    private String requestNo;

    @TableField(value = "batch_no", updateStrategy = FieldStrategy.ALWAYS)
    private String batchNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "scene", updateStrategy = FieldStrategy.ALWAYS)
    private String scene;

    @TableField(value = "document_key", updateStrategy = FieldStrategy.ALWAYS)
    private String documentKey;

    @TableField(value = "route_rule_key", updateStrategy = FieldStrategy.ALWAYS)
    private String routeRuleKey;

    @TableField(value = "target_key", updateStrategy = FieldStrategy.ALWAYS)
    private String targetKey;

    @TableField(value = "copy_index", updateStrategy = FieldStrategy.ALWAYS)
    private Integer copyIndex;

    @TableField(value = "required", updateStrategy = FieldStrategy.ALWAYS)
    private Integer required;

    @TableField(value = "printer_no", updateStrategy = FieldStrategy.ALWAYS)
    private String printerNo;

    @TableField(value = "template_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String templateVersionNo;

    @TableField(value = "renderer_version", updateStrategy = FieldStrategy.ALWAYS)
    private String rendererVersion;

    @TableField(value = "layout_profile_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String layoutProfileSnapshot;

    @TableField(value = "layout_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String layoutSnapshot;

    @TableField(value = "template_resolution_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String templateResolutionSnapshot;

    @TableField(value = "payload_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String payloadSnapshot;

    @TableField(value = "document_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String documentSnapshot;

    @TableField(value = "document_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String documentHash;

    @TableField(value = "route_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String routeSnapshot;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "priority", updateStrategy = FieldStrategy.ALWAYS)
    private Integer priority;

    @TableField(value = "printer_sequence", updateStrategy = FieldStrategy.ALWAYS)
    private Long printerSequence;

    @TableField(value = "retry_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer retryCount;

    @TableField(value = "max_retry_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxRetryCount;

    @TableField(value = "next_retry_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRetryAt;

    @TableField(value = "deadline_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime deadlineAt;

    @TableField(value = "current_attempt_no", updateStrategy = FieldStrategy.ALWAYS)
    private String currentAttemptNo;

    @TableField(value = "result_evidence", updateStrategy = FieldStrategy.ALWAYS)
    private String resultEvidence;

    @TableField(value = "confirmed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime confirmedAt;

    @TableField(value = "last_error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;

    @TableField(value = "last_error_summary", updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorSummary;

    @TableField(value = "parent_job_no", updateStrategy = FieldStrategy.ALWAYS)
    private String parentJobNo;

    @TableField(value = "root_job_no", updateStrategy = FieldStrategy.ALWAYS)
    private String rootJobNo;

    @TableField(value = "job_kind", updateStrategy = FieldStrategy.ALWAYS)
    private String jobKind;

    @TableField(value = "resolution_status", updateStrategy = FieldStrategy.ALWAYS)
    private String resolutionStatus;

    @TableField(value = "replacement_job_no", updateStrategy = FieldStrategy.ALWAYS)
    private String replacementJobNo;

    @TableField(value = "cancel_requested_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime cancelRequestedAt;

    @TableField(value = "finished_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}
