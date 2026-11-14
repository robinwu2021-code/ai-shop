package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prn_template_catalog")
public class PrnTemplate extends PrnRow {
    @TableField(value = "template_no", updateStrategy = FieldStrategy.ALWAYS)
    private String templateNo;

    @TableField(value = "scope_key", updateStrategy = FieldStrategy.ALWAYS)
    private String scopeKey;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "scene", updateStrategy = FieldStrategy.ALWAYS)
    private String scene;

    @TableField(value = "variant_code", updateStrategy = FieldStrategy.ALWAYS)
    private String variantCode;

    @TableField(value = "name", updateStrategy = FieldStrategy.ALWAYS)
    private String name;

    @TableField(value = "current_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String currentVersionNo;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;
}
