package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_incident")
public class PrnIncident extends PrnRow {
    @TableField(value = "incident_no", updateStrategy = FieldStrategy.ALWAYS)
    private String incidentNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "incident_type", updateStrategy = FieldStrategy.ALWAYS)
    private String incidentType;

    @TableField(value = "target_type", updateStrategy = FieldStrategy.ALWAYS)
    private String targetType;

    @TableField(value = "target_no", updateStrategy = FieldStrategy.ALWAYS)
    private String targetNo;

    @TableField(value = "incident_key", updateStrategy = FieldStrategy.ALWAYS)
    private String incidentKey;

    @TableField(value = "severity", updateStrategy = FieldStrategy.ALWAYS)
    private String severity;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "occurrence_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer occurrenceCount;

    @TableField(value = "first_seen_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime firstSeenAt;

    @TableField(value = "last_seen_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastSeenAt;

    @TableField(value = "acknowledged_by", updateStrategy = FieldStrategy.ALWAYS)
    private String acknowledgedBy;

    @TableField(value = "acknowledged_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime acknowledgedAt;

    @TableField(value = "resolved_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resolvedAt;

    @TableField(value = "resolution_code", updateStrategy = FieldStrategy.ALWAYS)
    private String resolutionCode;

    @TableField(value = "details", updateStrategy = FieldStrategy.ALWAYS)
    private String details;
}
