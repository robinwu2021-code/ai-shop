package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Getter;
import lombok.Setter;

/** Existing draft modifier head; referencing it does not grant published price or inventory authority. */
@Getter
@Setter
@TableName("fnb_modifier")
public class FnbModifier extends BaseEntity {
    private String entityNo;
    private String modifierNo;
    private String name;
    private String kind;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String salesSkuNo;
    private String publishedRevisionNo;
    private String status;
}
