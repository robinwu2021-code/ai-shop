package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 买家面的库存投影。<b>没有供应商列，也没有精确数量</b> —— 这张表是买家侧唯一能读的库存。
 */
@Getter
@Setter
@TableName("elc_part_market")
public class ElcPartMarket extends ElcMutableEntity {

    private String partNo;

    private String qtyBand;

    private String sourceBand;

    private Long priceFromE6;

    private Integer dcYearMax;

    private java.time.LocalDateTime nextExpiryAt;

    private java.time.LocalDateTime refreshedAt;
}
