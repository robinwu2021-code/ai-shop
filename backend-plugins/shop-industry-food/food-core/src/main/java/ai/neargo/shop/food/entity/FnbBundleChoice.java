package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Editable restaurant composition; immutable publications are stored separately. */
@Getter
@Setter
@TableName("fnb_bundle_choice")
public class FnbBundleChoice extends BaseEntity {
    private String entityNo;
    private String choiceNo;
    private String groupNo;
    private String skuNo;
    private BigDecimal unitsPerPick;
    private BigDecimal minQty;
    private BigDecimal maxQty;
    private BigDecimal defaultQty;
    private String priceMode;
    private Integer sortNo;
}
