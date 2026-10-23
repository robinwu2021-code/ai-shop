package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Original restaurant transaction table; monetary authority remains in core and PAY. */
@Getter
@Setter
@TableName("fnb_order_component")
public class FnbOrderComponent extends BaseEntity {
    private String entityNo;
    private String componentNo;
    private String foodLineNo;
    private String choiceNo;
    private String skuNo;
    private BigDecimal qty;
    private String selectionSnapshot;
}
