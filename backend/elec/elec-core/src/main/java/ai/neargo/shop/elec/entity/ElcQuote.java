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
    /** 这一行成交给了别家。只改状态、不推通知：供应商打开求购列表就看得到 */
    public static final String STATUS_NOT_CHOSEN = "NOT_CHOSEN";
    /** 目前没有代码把它写进库：有效期过了的 ACTIVE 在读出时显示成它 */
    public static final String STATUS_EXPIRED = "EXPIRED";

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

    /** 首次报价通知送到买家的时间；空 = 没送到（改价不再通知，也是空） */
    private java.time.LocalDateTime buyerNotifiedAt;

    private String status;
}
