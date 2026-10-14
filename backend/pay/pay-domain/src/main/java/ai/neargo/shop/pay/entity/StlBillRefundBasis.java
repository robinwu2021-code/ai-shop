package ai.neargo.shop.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 只插入的退款原值；不继承可更新账单，版本由basisVersion固定。 */
@Getter
@Setter
@TableName("stl_bill_refund_basis")
public class StlBillRefundBasis {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String settleNo;
    private String subOrderNo;
    private Integer basisVersion;
    private String source;
    private String status;
    private String reviewReason;
    private Long goodsPaidMinor;
    private Long grossMinor;
    private Long merchantGoodsMinor;
    private Long commissionMinor;
    private Long serviceFeeMinor;
    private Long pointsFeeMinor;
    private Long pointsCostMinor;
    private Long subsidyMinor;
    private Long freightIncomeMinor;
    private Long freightCostMinor;
    private String freightShipMode;
    private String paymentRoute;
    private String originPaymentNo;
    private String accountSnapshotRef;
    private String payMerchantNo;
    private String payChannel;
    private String payScene;
    private String currency;
    private String feeBearer;
    private String wxAppid;
    private String tenantNo;
    private LocalDateTime createdAt;
}
