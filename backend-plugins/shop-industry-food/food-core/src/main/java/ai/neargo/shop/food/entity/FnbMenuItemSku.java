package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Mutable restaurant-owned structure; product, SKU and group facts remain in their original owners. */
@Getter
@Setter
@TableName("fnb_menu_item_sku")
public class FnbMenuItemSku extends BaseEntity {
    private String entityNo;
    private String menuItemNo;
    private String skuNo;
    private String selectionMode;
    private String recipeNo;
}
