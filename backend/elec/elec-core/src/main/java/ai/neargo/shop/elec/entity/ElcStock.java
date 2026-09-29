package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_stock")
public class ElcStock extends ElcMutableEntity {

    public static final String STATUS_ON = "ON";
    public static final String STATUS_DELISTED = "DELISTED";

    private String stockNo;

    private String supplierNo;

    private String lineKey;

    private String partNo;

    private String mpnRaw;

    private String mfrRaw;

    private String mpnNorm;

    private Long qty;

    private String dateCode;

    private Integer dcYear;

    /** 封装，如 LQFP-48 / 0402 */
    private String pkg;

    private Integer moq;

    /** 最小包装量。只有 MOQ 没有 SPQ，报出去的价会被推翻（买 2000 可能被迫要一整盘 5000） */
    private Integer spq;

    /** 阶梯价 JSON，按 minQty 升序。**元器件报价天生是阶梯的** */
    private String priceTiers;

    /** 阶梯里 minQty 最小的那一档，冗余出来给排序与投影用 */
    private Long priceE6;

    private String currency;

    private Boolean taxIncluded;

    /** REEL / TRAY / TUBE / CUT_TAPE / BULK / BOX */
    private String packing;

    /** ORIGINAL / LOOSE / PULLED / REFURB —— 元器件最要命的质量维度，价差好几倍 */
    private String condGrade;

    /** 交期天数，0 = 现货；空 = 供应商没说 */
    private Integer leadDays;

    private String region;

    private java.time.LocalDate validUntil;

    private java.time.LocalDateTime confirmedAt;

    private String status;

    private String batchNo;
}
