package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_template_version")
public class PrnTemplateVersion extends PrnRow {
    @TableField(value = "template_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String templateVersionNo;

    @TableField(value = "template_no", updateStrategy = FieldStrategy.ALWAYS)
    private String templateNo;

    @TableField(value = "version_number", updateStrategy = FieldStrategy.ALWAYS)
    private Integer versionNumber;

    @TableField(value = "format", updateStrategy = FieldStrategy.ALWAYS)
    private String format;

    @TableField(value = "schema_version", updateStrategy = FieldStrategy.ALWAYS)
    private Integer schemaVersion;

    @TableField(value = "payload_schema_version", updateStrategy = FieldStrategy.ALWAYS)
    private Integer payloadSchemaVersion;

    @TableField(value = "paper_width_mm", updateStrategy = FieldStrategy.ALWAYS)
    private Integer paperWidthMm;

    @TableField(value = "content", updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    @TableField(value = "content_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String contentHash;

    @TableField(value = "compatible_capabilities", updateStrategy = FieldStrategy.ALWAYS)
    private String compatibleCapabilities;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "published_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime publishedAt;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;
}
