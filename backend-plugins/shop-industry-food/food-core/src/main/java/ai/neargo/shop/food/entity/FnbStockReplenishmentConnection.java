package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("fnb_stock_replenishment_connection")
public class FnbStockReplenishmentConnection extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String status;
    private Long generation;
    private String sourceSystem;
    private String sourceOwner;
    private Long postingWatermark;
}
