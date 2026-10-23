package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Existing V1 management configuration; BaseEntity audit and logical deletion remain authoritative. */
@Getter
@Setter
@TableName("fnb_dish_option_group")
public class FnbDishOptionGroup extends BaseEntity {
    private String entityNo;
    private String goodsNo;
    private String groupNo;
    private Integer sortNo;
}
