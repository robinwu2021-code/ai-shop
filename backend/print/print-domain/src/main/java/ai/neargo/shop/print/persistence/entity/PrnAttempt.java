package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_attempt")
public class PrnAttempt extends PrnRow {
    @TableField(value = "attempt_no", updateStrategy = FieldStrategy.ALWAYS)
    private String attemptNo;

    @TableField(value = "job_no", updateStrategy = FieldStrategy.ALWAYS)
    private String jobNo;

    @TableField(value = "printer_no", updateStrategy = FieldStrategy.ALWAYS)
    private String printerNo;

    @TableField(value = "attempt_index", updateStrategy = FieldStrategy.ALWAYS)
    private Integer attemptIndex;

    @TableField(value = "dispatch_key", updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchKey;

    @TableField(value = "agent_no", updateStrategy = FieldStrategy.ALWAYS)
    private String agentNo;

    @TableField(value = "agent_session_epoch", updateStrategy = FieldStrategy.ALWAYS)
    private Long agentSessionEpoch;

    @TableField(value = "fence_epoch", updateStrategy = FieldStrategy.ALWAYS)
    private Long fenceEpoch;

    @TableField(value = "connection_revision", updateStrategy = FieldStrategy.ALWAYS)
    private Long connectionRevision;

    @TableField(value = "credential_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String credentialRef;

    @TableField(value = "connection_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String connectionSnapshot;

    @TableField(value = "adapter_version", updateStrategy = FieldStrategy.ALWAYS)
    private String adapterVersion;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "provider_scope", updateStrategy = FieldStrategy.ALWAYS)
    private String providerScope;

    @TableField(value = "provider_job_id", updateStrategy = FieldStrategy.ALWAYS)
    private String providerJobId;

    @TableField(value = "provider_reference_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String providerReferenceHash;

    @TableField(value = "request_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String requestHash;

    @TableField(value = "transport_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String transportHash;

    @TableField(value = "submitted_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime submittedAt;

    @TableField(value = "accepted_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime acceptedAt;

    @TableField(value = "result_observed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resultObservedAt;

    @TableField(value = "next_query_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextQueryAt;

    @TableField(value = "last_error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;

    @TableField(value = "result_summary", updateStrategy = FieldStrategy.ALWAYS)
    private String resultSummary;

    @TableField(value = "finished_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}
