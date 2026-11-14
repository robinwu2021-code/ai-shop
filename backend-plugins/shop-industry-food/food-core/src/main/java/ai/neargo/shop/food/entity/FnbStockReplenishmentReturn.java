package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter @TableName("fnb_stock_replenishment_return")
public class FnbStockReplenishmentReturn extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String replenishmentNo;
    private String sourceSystem;
    private String sourceOwner;
    private String sourceDocument;
    private Integer sourceLine;
    private BigDecimal acceptedQuantity;
}
