package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Existing V1 management configuration; BaseEntity audit and logical deletion remain authoritative. */
@Getter
@Setter
@TableName("fnb_option_group_item")
public class FnbOptionGroupItem extends BaseEntity {
    private String entityNo;
    private String optionItemNo;
    private String groupNo;
    private String sourceKind;
    private String specValueNo;
    private String modifierNo;
    private BigDecimal minQty;
    private BigDecimal maxQty;
    private BigDecimal defaultQty;
    private Integer sortNo;
}
