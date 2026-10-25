package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Mutable restaurant-owned structure; product, SKU and group facts remain in their original owners.
 */
@Getter
@Setter
@TableName("fnb_menu_item")
public class FnbMenuItem extends BaseEntity {
    private String entityNo;
    private String menuItemNo;
    private String categoryNo;
    private String goodsNo;
    private Integer sortNo;
    private Boolean enabled;
    private String skuScope;
    private String optionMode;
    private String priceOverrides;
}
