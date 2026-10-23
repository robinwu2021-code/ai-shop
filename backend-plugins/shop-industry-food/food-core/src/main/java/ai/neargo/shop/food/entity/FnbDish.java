package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Product-level extension; identity, price and stock remain in the original domains. */
@Getter
@Setter
@TableName("fnb_dish")
public class FnbDish extends BaseEntity {
    private String entityNo;
    private String goodsNo;
    private String dishKind;
    private Integer prepSeconds;
    private String status;
    private String presentation;
    private String measurementPolicy;
    private Long configVersion;
}
