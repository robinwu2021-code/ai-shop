package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_reconcile_cursor")
public class PrnReconcileCursor extends PrnRow {
    @TableField(value = "cursor_no", updateStrategy = FieldStrategy.ALWAYS)
    private String cursorNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "source_kind", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceKind;

    @TableField(value = "watermark_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime watermarkAt;

    @TableField(value = "watermark_no", updateStrategy = FieldStrategy.ALWAYS)
    private String watermarkNo;

    @TableField(value = "overlap_seconds", updateStrategy = FieldStrategy.ALWAYS)
    private Integer overlapSeconds;

    @TableField(value = "last_full_scan_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastFullScanAt;

    @TableField(value = "lease_owner", updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;

    @TableField(value = "lease_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

    @TableField(value = "last_success_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastSuccessAt;

    @TableField(value = "last_error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;
}
