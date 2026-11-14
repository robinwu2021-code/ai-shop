package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Durable stock admission metadata; never an additional stock balance. */
@Getter
@Setter
@TableName("prd_stock_replenishment_connection")
public class PrdStockReplenishmentConnection extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private Integer enabled;
    private Long generation;
    private String sourceSystem;
    private String sourceOwner;
    private Long postingWatermark;
}
