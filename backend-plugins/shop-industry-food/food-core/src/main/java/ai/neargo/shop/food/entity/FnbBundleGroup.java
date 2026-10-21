package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Editable restaurant composition; immutable publications are stored separately. */
@Getter
@Setter
@TableName("fnb_bundle_group")
public class FnbBundleGroup extends BaseEntity {
    private String entityNo;
    private String groupNo;
    private String bundleSkuNo;
    private String name;
    private BigDecimal minQty;
    private BigDecimal maxQty;
    private String baseSkuNo;
    private Integer sortNo;
}
