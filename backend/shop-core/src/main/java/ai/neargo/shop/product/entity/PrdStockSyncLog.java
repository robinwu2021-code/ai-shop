package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 进销存 → 商城写回明细（V346，TDD §6.2 第 6 步）。
 * 唯一键 {@code (source_ref, store_no, sku_no)} 即幂等键：同一张单据重投只写一次。
 * 店主据它看到「线上为什么从 18 变成 23」。
 */
@Getter
@Setter
@TableName("prd_stock_sync_log")
public class PrdStockSyncLog extends BaseEntity {

    private String storeNo;
    private String skuNo;
    private String sourceRef;
    private String ruleType;
    private Integer available;
    private Integer beforeQty;
    private Integer afterQty;
}
