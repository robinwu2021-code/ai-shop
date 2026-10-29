package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Restaurant-owned balance for one item in one store. */
@Getter
@Setter
@TableName("fnb_stock_balance")
public class FnbStockBalance extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String balanceNo;
    private String itemNo;
    private BigDecimal onHand;
    private BigDecimal reservedQty;
}
