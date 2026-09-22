package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 门店库存同步开关与期初对齐（V346，TDD §7 / §18）。每店一行。
 * <b>没对齐（{@code alignedAt} 为空）不许打开</b>：带着两本账的旧差额开写回，第一天就不对。
 */
@Getter
@Setter
@TableName("prd_store_stock_sync")
public class PrdStoreStockSync extends BaseEntity {

    public static final String ALIGN_MALL = "MALL";
    public static final String ALIGN_COUNT = "COUNT";

    private String storeNo;
    private String entityNo;
    /** 1 进销存过账后写回商城 */
    private Integer enabled;
    private LocalDateTime alignedAt;
    private String alignedBy;
    private String alignMode;
}
