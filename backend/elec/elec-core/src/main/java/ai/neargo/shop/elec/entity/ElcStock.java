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

    private Long priceE6;

    private Boolean taxIncluded;

    private java.time.LocalDate validUntil;

    private java.time.LocalDateTime confirmedAt;

    private String status;

    private String batchNo;
}
