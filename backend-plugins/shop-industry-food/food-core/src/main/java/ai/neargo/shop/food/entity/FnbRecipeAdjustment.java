package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Mutable recipe-specific references; executable selections use future frozen revisions. */
@Getter
@Setter
@TableName("fnb_recipe_adjustment")
public class FnbRecipeAdjustment extends BaseEntity {
    private String entityNo;
    private String adjustmentNo;
    private String recipeNo;
    private String optionItemNo;
    private String targetLineNo;
    private String action;
    private BigDecimal qty;
    private String unit;
    private String optionIdentityHash;
    private Integer sortNo;
}
