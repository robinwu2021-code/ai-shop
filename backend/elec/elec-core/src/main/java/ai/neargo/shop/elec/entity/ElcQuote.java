package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 供应商报价（他填的原样）。
 *
 * <p><b>买家看到的不是这张表</b>：价要按平台规则加价、供应商要匿名化，那一层在服务里做。
 */
@Getter
@Setter
@TableName("elc_quote")
public class ElcQuote extends ElcMutableEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    private String quoteNo;

    private String dispatchNo;

    private String rfqNo;

    private Integer lineNo;

    private String supplierNo;

    private Long priceE6;

    private String currency;

    private Boolean taxIncluded;

    private Long qtyAvailable;

    private String dateCode;

    private Integer dcYear;

    private Integer leadDays;

    private String condGrade;

    private String packing;

    private Integer moq;

    private LocalDate validUntil;

    /** 只给平台看 —— 供应商常在这里写公司名和微信 */
    private String remark;

    private String status;
}
