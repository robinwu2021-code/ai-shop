package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_export_task")
public class PrnExportTask extends PrnRow {
    @TableField(value = "export_no", updateStrategy = FieldStrategy.ALWAYS)
    private String exportNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "operation_no", updateStrategy = FieldStrategy.ALWAYS)
    private String operationNo;

    @TableField(value = "export_key", updateStrategy = FieldStrategy.ALWAYS)
    private String exportKey;

    @TableField(value = "source_type", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceType;

    @TableField(value = "source_no", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceNo;

    @TableField(value = "source_revision", updateStrategy = FieldStrategy.ALWAYS)
    private Long sourceRevision;

    @TableField(value = "source_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceSnapshot;

    @TableField(value = "output_profile", updateStrategy = FieldStrategy.ALWAYS)
    private String outputProfile;

    @TableField(value = "artifact_kind", updateStrategy = FieldStrategy.ALWAYS)
    private String artifactKind;

    @TableField(value = "document_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String documentHash;

    @TableField(value = "renderer_version", updateStrategy = FieldStrategy.ALWAYS)
    private String rendererVersion;

    @TableField(value = "font_bundle_version", updateStrategy = FieldStrategy.ALWAYS)
    private String fontBundleVersion;

    @TableField(value = "required_data_scopes", updateStrategy = FieldStrategy.ALWAYS)
    private String requiredDataScopes;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "object_key", updateStrategy = FieldStrategy.ALWAYS)
    private String objectKey;

    @TableField(value = "content_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String contentHash;

    @TableField(value = "content_type", updateStrategy = FieldStrategy.ALWAYS)
    private String contentType;

    @TableField(value = "file_size", updateStrategy = FieldStrategy.ALWAYS)
    private Long fileSize;

    @TableField(value = "page_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer pageCount;

    @TableField(value = "claim_token_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String claimTokenHash;

    @TableField(value = "lease_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

    @TableField(value = "next_retry_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRetryAt;

    @TableField(value = "retry_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer retryCount;

    @TableField(value = "generation_epoch", updateStrategy = FieldStrategy.ALWAYS)
    private Long generationEpoch;

    @TableField(value = "last_error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;

    @TableField(value = "ready_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime readyAt;

    @TableField(value = "expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime expiresAt;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;

    @TableField(value = "object_deleted_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime objectDeletedAt;
}
