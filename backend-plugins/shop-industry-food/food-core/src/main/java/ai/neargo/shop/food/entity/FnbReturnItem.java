package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Original FOOD return source facts; financial and kitchen tracks remain independent. */
@Getter
@Setter
@TableName("fnb_return_item")
public class FnbReturnItem extends BaseEntity {
    private String entityNo;
    private String returnItemNo;
    private String returnNo;
    private String foodLineNo;
    private String componentNo;
    private BigDecimal qty;
    private String kitchenDisposition;
    private String coreLineAllocationRef;
}
