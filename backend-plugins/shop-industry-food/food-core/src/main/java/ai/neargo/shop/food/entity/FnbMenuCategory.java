package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Mutable restaurant-owned structure; product, SKU and group facts remain in their original owners. */
@Getter
@Setter
@TableName("fnb_menu_category")
public class FnbMenuCategory extends BaseEntity {
    private String entityNo;
    private String menuNo;
    private String categoryNo;
    private String name;
    private Integer sortNo;
}
