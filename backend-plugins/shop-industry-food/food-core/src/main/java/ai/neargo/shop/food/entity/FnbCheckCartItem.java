package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Restaurant draft persisted in the original frozen V1 table. */
@Getter
@Setter
@TableName("fnb_check_cart_item")
public class FnbCheckCartItem extends BaseEntity {
    private String entityNo;
    private String cartItemNo;
    private String cartNo;
    private String sourceLineRef;
    private String goodsNo;
    private String skuNo;
    private BigDecimal qty;
    private String seatNo;
    private String courseNo;
    private String selection;
    private String addedBy;
}
