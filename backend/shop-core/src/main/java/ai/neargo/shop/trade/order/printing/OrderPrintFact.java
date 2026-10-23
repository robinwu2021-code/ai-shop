package ai.neargo.shop.trade.order.printing;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_print_fact")
public class OrderPrintFact {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_no")
    private String tenantNo;

    @TableField("entity_no")
    private String entityNo;

    @TableField("store_no")
    private String storeNo;

    @TableField("print_fact_no")
    private String printFactNo;

    @TableField("order_no")
    private String orderNo;

    @TableField("sub_order_no")
    private String subOrderNo;

    @TableField("source_fact_key")
    private String sourceFactKey;

    @TableField("receipt_intent_key")
    private String receiptIntentKey;

    @TableField("biz_revision")
    private String bizRevision;

    @TableField("scene")
    private String scene;

    @TableField("trigger_type")
    private String triggerType;

    @TableField("payload_schema_version")
    private Integer payloadSchemaVersion;

    @TableField("source_occurred_at")
    private LocalDateTime sourceOccurredAt;

    @TableField("snapshot")
    private String snapshot;

    @TableField("snapshot_hash")
    private String snapshotHash;

    @TableField("printability")
    private String printability;

    @TableField("block_code")
    private String blockCode;

    @TableField("diagnostics")
    private String diagnostics;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
