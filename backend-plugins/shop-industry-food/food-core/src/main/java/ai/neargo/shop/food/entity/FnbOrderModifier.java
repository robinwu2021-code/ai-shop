package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Original restaurant transaction table; monetary authority remains in core and PAY. */
@Getter
@Setter
@TableName("fnb_order_modifier")
public class FnbOrderModifier extends BaseEntity {
    private String entityNo;
    private String selectionNo;
    private String foodLineNo;
    private String componentNo;
    private String sourceKind;
    private String sourceNo;
    private BigDecimal qty;
    private String selectionSnapshot;
}
