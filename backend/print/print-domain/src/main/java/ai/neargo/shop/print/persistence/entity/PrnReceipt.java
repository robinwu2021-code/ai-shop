package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_receipt")
public class PrnReceipt extends PrnRow {
    @TableField(value = "receipt_no", updateStrategy = FieldStrategy.ALWAYS)
    private String receiptNo;

    @TableField(value = "attempt_no", updateStrategy = FieldStrategy.ALWAYS)
    private String attemptNo;

    @TableField(value = "source_type", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceType;

    @TableField(value = "source_scope", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceScope;

    @TableField(value = "source_receipt_key", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceReceiptKey;

    @TableField(value = "reported_status", updateStrategy = FieldStrategy.ALWAYS)
    private String reportedStatus;

    @TableField(value = "evidence_level", updateStrategy = FieldStrategy.ALWAYS)
    private String evidenceLevel;

    @TableField(value = "source_sequence", updateStrategy = FieldStrategy.ALWAYS)
    private Long sourceSequence;

    @TableField(value = "source_occurred_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime sourceOccurredAt;

    @TableField(value = "received_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime receivedAt;

    @TableField(value = "payload_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String payloadHash;

    @TableField(value = "sanitized_payload", updateStrategy = FieldStrategy.ALWAYS)
    private String sanitizedPayload;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "process_error", updateStrategy = FieldStrategy.ALWAYS)
    private String processError;

    @TableField(value = "processed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime processedAt;
}
