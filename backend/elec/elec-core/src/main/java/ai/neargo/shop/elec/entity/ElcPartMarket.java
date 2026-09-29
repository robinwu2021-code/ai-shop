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

    /** 含税参考起价，**统一换算成人民币** */
    private Long priceFromE6;

    /** 这个价从多少片起。有了阶梯价就必须说 —— 只写「¥6.85 起」会让按 10 片来询的人觉得被坑 */
    private Long priceFromQty;

    private Integer dcYearMax;

    private Boolean spot;

    private Integer leadDaysMin;

    /** 这个料号有哪些货况，逗号分隔 */
    private String condSet;

    private java.time.LocalDateTime nextExpiryAt;

    private java.time.LocalDateTime refreshedAt;
}
