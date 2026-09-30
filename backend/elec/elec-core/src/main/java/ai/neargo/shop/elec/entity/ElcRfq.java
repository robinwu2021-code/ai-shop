package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_rfq")
public class ElcRfq extends ElcMutableEntity {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_QUOTED = "QUOTED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_CLOSED = "CLOSED";
    /** 不落库：QUOTED 且过了报价有效期时，对外显示成它 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    private String rfqNo;

    private String buyerRef;

    private String contactPhone;

    private String contactName;

    private String company;

    private String needInvoice;

    private String dcReq;

    /** ANY / ORIGINAL 只要原装原包 / NEW 原装即可 */
    private String condReq;

    /** ANY / REEL 必须整盘 / CUT_TAPE 可以剪带 */
    private String packingReq;

    /** 几天内要到货；空 = 不急 */
    private Integer needByDays;

    /** 能不能用替代/兼容型号 */
    private Boolean allowAlt;

    private String deliverCity;

    private String remark;

    private Integer lineCnt;

    /** 派给了几家 */
    private Integer dispatchCnt;

    /** 有几家报了价 */
    private Integer quoteCnt;

    private String status;

    private java.time.LocalDateTime notifiedAt;

    private java.time.LocalDateTime quotedAt;

    private String quotedBy;

    private java.time.LocalDate quoteValidUntil;

    private String quoteNote;

    private java.time.LocalDateTime buyerNotifiedAt;

    /** 买家上次看详情时看到的最大报价 id；比它大的有效报价就是「新报价」 */
    private Long buyerSeenQuoteId;

    /** 买家上次看详情时 quotedAt 的原样副本；不等 = 平台报了新价。原样比，不经过时钟 */
    private java.time.LocalDateTime buyerSeenQuotedAt;

    private java.time.LocalDateTime acceptedAt;

    private java.time.LocalDateTime closedAt;

    private String closeReason;
}
