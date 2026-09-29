package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 派单：供应商能看到求购需求的唯一通道。
 *
 * <p><b>供应商拿到的是 dispatch_no，不是 rfq_no</b> —— 两边拿不到同一个号，也就对不上。
 * 他看得到料号、数量、要求，看不到买家是谁。
 */
@Getter
@Setter
@TableName("elc_dispatch")
public class ElcDispatch extends ElcMutableEntity {

    public static final String VIA_AUTO = "AUTO_MATCH";
    public static final String VIA_OPS = "OPS";

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_VIEWED = "VIEWED";
    public static final String STATUS_QUOTED = "QUOTED";
    public static final String STATUS_DECLINED = "DECLINED";

    private String dispatchNo;

    private String rfqNo;

    private Integer lineNo;

    private String supplierNo;

    private String via;

    private String status;

    private String declineReason;

    private LocalDateTime notifiedAt;

    private LocalDateTime viewedAt;

    private LocalDateTime respondedAt;
}
