package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Original restaurant transaction table; monetary authority remains in core and PAY. */
@Getter
@Setter
@TableName("fnb_order_line")
public class FnbOrderLine extends BaseEntity {
    private String entityNo;
    private String foodLineNo;
    private String batchNo;
    private String goodsNo;
    private String skuNo;
    private BigDecimal qty;
    private String seatNo;
    private String courseNo;
    private Long selectionVersion;
    private String displaySnapshot;
    private String sourceLineRef;
    private String configRevisionNos;
    private String unit;
}
