package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Mutable restaurant-owned structure; product, SKU and group facts remain in their original owners. */
@Getter
@Setter
@TableName("fnb_menu_item_option_group")
public class FnbMenuItemOptionGroup extends BaseEntity {
    private String entityNo;
    private String menuItemNo;
    private String groupNo;
    private Integer sortNo;
}
