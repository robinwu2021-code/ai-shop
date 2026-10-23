package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_printer")
public class PrnPrinter extends PrnRow {
    @TableField(value = "printer_no", updateStrategy = FieldStrategy.ALWAYS)
    private String printerNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "name", updateStrategy = FieldStrategy.ALWAYS)
    private String name;

    @TableField(value = "physical_key", updateStrategy = FieldStrategy.ALWAYS)
    private String physicalKey;

    @TableField(value = "connection_type", updateStrategy = FieldStrategy.ALWAYS)
    private String connectionType;

    @TableField(value = "adapter_code", updateStrategy = FieldStrategy.ALWAYS)
    private String adapterCode;

    @TableField(value = "vendor_account_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String vendorAccountRef;

    @TableField(value = "device_sn", updateStrategy = FieldStrategy.ALWAYS)
    private String deviceSn;

    @TableField(value = "agent_no", updateStrategy = FieldStrategy.ALWAYS)
    private String agentNo;

    @TableField(value = "connection_config", updateStrategy = FieldStrategy.ALWAYS)
    private String connectionConfig;

    @TableField(value = "credential_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String credentialRef;

    @TableField(value = "paper_width_mm", updateStrategy = FieldStrategy.ALWAYS)
    private Integer paperWidthMm;

    @TableField(value = "capabilities", updateStrategy = FieldStrategy.ALWAYS)
    private String capabilities;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "health_status", updateStrategy = FieldStrategy.ALWAYS)
    private String healthStatus;

    @TableField(value = "health_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String healthReason;

    @TableField(value = "health_observed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime healthObservedAt;

    @TableField(value = "connection_revision", updateStrategy = FieldStrategy.ALWAYS)
    private Long connectionRevision;
}
