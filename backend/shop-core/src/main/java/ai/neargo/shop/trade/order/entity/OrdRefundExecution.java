package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** The immutable refund plan survives gateway ambiguity and independent resource recovery. */
@Getter
@Setter
@TableName("ord_refund_execution")
public class OrdRefundExecution extends BaseEntity {
    public static final String PREPARED = "PREPARED";
    public static final String PROCESSING = "PROCESSING";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String FAILED = "FAILED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REVERSAL = "REVERSAL";
    public static final String CASH = "CASH";
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    private String executionNo;
    private String afterSaleNo;
    private String subOrderNo;
    private String orderNo;
    private String originPaymentNo;
    private String payChannel;
    private Long amountMinor;
    private String currency;
    private String planJson;
    private String stockNeedsJson;
    private String stockAuthority;
    private Boolean restoreStock;
    private Boolean restockApproved;
    private Boolean zeroLiability;
    private String reason;
    private String status;
    private String phase;
    private String paymentNo;
    private Long nextAttemptAt;
    private String errorCode;
    private String resourceStatus;
    private String resourceErrorCode;
    private Long succeededAt;
    private Integer closureVersion;
    private String closureStatus;
    private Boolean returnCoupon;
}
