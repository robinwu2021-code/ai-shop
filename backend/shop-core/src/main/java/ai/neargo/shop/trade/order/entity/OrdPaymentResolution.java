package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Original financial evidence for a cash payment that cannot be applied to its order. */
@Getter
@Setter
@TableName("ord_payment_resolution")
public class OrdPaymentResolution extends BaseEntity {
    public static final String MANUAL_REQUIRED = "MANUAL_REQUIRED";
    public static final String RESOLVED = "RESOLVED";
    public static final String LATE_PAYMENT = "LATE_PAYMENT";
    public static final String RECEIPT_MISMATCH = "RECEIPT_MISMATCH";
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";

    private String payNo;
    private String paymentNo;
    private String userNo;
    private String sourceUserNo;
    private String sourcePayNo;
    private Long amountMinor;
    private String currency;
    private String payChannel;
    private String tradeNo;
    private String reason;
    private String status;
    private String refundNo;
}
