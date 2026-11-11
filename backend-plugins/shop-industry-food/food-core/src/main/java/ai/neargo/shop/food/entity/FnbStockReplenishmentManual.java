package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter @TableName("fnb_stock_replenishment_manual")
public class FnbStockReplenishmentManual extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String replenishmentNo;
    private String movementNo;
    private BigDecimal quantity;
}
