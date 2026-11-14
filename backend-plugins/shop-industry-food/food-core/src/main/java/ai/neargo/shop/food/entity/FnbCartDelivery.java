package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Keeps the FnbCartDelivery business boundary scoped to original restaurant facts and lifecycle
 * authority.
 */
@Getter
@Setter
@TableName("fnb_cart_delivery")
public class FnbCartDelivery extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String cartNo;
    private String receiverName;
    private String receiverPhone;
    private String address;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String deliveryInstruction;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer latE6;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer lngE6;

    private String commandHistory;
}
