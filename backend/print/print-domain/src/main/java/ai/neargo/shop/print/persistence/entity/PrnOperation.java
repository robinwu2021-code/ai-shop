package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_operation")
public class PrnOperation extends PrnRow {
    @TableField(value = "operation_no", updateStrategy = FieldStrategy.ALWAYS)
    private String operationNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "operation_type", updateStrategy = FieldStrategy.ALWAYS)
    private String operationType;

    @TableField(value = "actor_type", updateStrategy = FieldStrategy.ALWAYS)
    private String actorType;

    @TableField(value = "actor_no", updateStrategy = FieldStrategy.ALWAYS)
    private String actorNo;

    @TableField(value = "idempotency_key", updateStrategy = FieldStrategy.ALWAYS)
    private String idempotencyKey;

    @TableField(value = "request_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String requestHash;

    @TableField(value = "target_type", updateStrategy = FieldStrategy.ALWAYS)
    private String targetType;

    @TableField(value = "target_no", updateStrategy = FieldStrategy.ALWAYS)
    private String targetNo;

    @TableField(value = "command_payload", updateStrategy = FieldStrategy.ALWAYS)
    private String commandPayload;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "result_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String resultRef;

    @TableField(value = "error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    @TableField(value = "reason", updateStrategy = FieldStrategy.ALWAYS)
    private String reason;

    @TableField(value = "next_check_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextCheckAt;

    @TableField(value = "finished_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}
