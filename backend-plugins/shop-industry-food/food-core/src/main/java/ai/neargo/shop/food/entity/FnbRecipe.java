package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Editable recipe working rows; historical consumers must use later immutable publications. */
@Getter
@Setter
@TableName("fnb_recipe")
public class FnbRecipe extends BaseEntity {
    private String recipeNo;
    private String entityNo;
    private String storeNo;
    private String skuNo;
    private String recipeCode;
    private BigDecimal yieldQty;
    private String yieldUnit;
    private Long configVersion;
    private String publishedRevisionNo;
    private String status;
}
