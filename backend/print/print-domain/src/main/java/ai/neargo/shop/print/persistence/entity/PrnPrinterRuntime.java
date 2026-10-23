package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_printer_runtime")
public class PrnPrinterRuntime extends PrnRow {
    @TableField(value = "printer_no", updateStrategy = FieldStrategy.ALWAYS)
    private String printerNo;

    @TableField(value = "active_job_no", updateStrategy = FieldStrategy.ALWAYS)
    private String activeJobNo;

    @TableField(value = "active_attempt_no", updateStrategy = FieldStrategy.ALWAYS)
    private String activeAttemptNo;

    @TableField(value = "lease_owner", updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;

    @TableField(value = "lease_token_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String leaseTokenHash;

    @TableField(value = "lease_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

    @TableField(value = "fence_epoch", updateStrategy = FieldStrategy.ALWAYS)
    private Long fenceEpoch;

    @TableField(value = "blocked_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String blockedReason;

    @TableField(value = "last_dispatch_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastDispatchAt;

    @TableField(value = "last_sequence", updateStrategy = FieldStrategy.ALWAYS)
    private Long lastSequence;
}
