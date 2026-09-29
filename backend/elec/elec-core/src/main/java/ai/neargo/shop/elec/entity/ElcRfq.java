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

    private String deliverCity;

    private String remark;

    private Integer lineCnt;

    private String status;

    private java.time.LocalDateTime notifiedAt;

    private java.time.LocalDateTime quotedAt;

    private String quotedBy;

    private java.time.LocalDate quoteValidUntil;

    private String quoteNote;

    private java.time.LocalDateTime buyerNotifiedAt;

    private java.time.LocalDateTime acceptedAt;

    private java.time.LocalDateTime closedAt;

    private String closeReason;
}
