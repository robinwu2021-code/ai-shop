package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_route_version")
public class PrnRouteVersion extends PrnRow {
    @TableField(value = "route_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String routeVersionNo;

    @TableField(value = "route_no", updateStrategy = FieldStrategy.ALWAYS)
    private String routeNo;

    @TableField(value = "version_number", updateStrategy = FieldStrategy.ALWAYS)
    private Integer versionNumber;

    @TableField(value = "rules", updateStrategy = FieldStrategy.ALWAYS)
    private String rules;

    @TableField(value = "content_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String contentHash;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "published_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime publishedAt;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;
}
