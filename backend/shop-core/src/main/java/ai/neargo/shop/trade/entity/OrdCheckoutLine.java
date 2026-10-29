package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ord_checkout_line")
public class OrdCheckoutLine extends BaseEntity {
    private String sourceLineNo;
    private String caller;
    private String operationNo;
    private String clientLineRef;
    private String subOrderNo;
    private Long orderItemId;
    private String goodsNo;
    private String skuNo;
    private Integer quantity;
    private String uom;
}
