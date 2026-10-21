package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Editable recipe working rows; historical consumers must use later immutable publications. */
@Getter
@Setter
@TableName("fnb_recipe_line")
public class FnbRecipeLine extends BaseEntity {
    private String lineNo;
    private String entityNo;
    private String recipeNo;
    private String itemRef;
    private BigDecimal qty;
    private String unit;
    private String conversionRef;
    private String materialDefinitionHash;
    private BigDecimal lossRate;
    private Integer sortNo;
}
