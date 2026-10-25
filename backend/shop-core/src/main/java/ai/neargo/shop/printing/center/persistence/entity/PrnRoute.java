package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prn_route_catalog")
public class PrnRoute extends PrnRow {
    @TableField(value = "route_no", updateStrategy = FieldStrategy.ALWAYS)
    private String routeNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "scene", updateStrategy = FieldStrategy.ALWAYS)
    private String scene;

    @TableField(value = "current_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String currentVersionNo;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;
}
