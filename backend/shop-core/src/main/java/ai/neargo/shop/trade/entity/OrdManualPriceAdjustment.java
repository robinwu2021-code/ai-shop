package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Versioned source-bound staff intent and real policy/owner review; no fund consumption occurs here. */
@Getter
@Setter
@TableName("ord_manual_price_adjustment")
public class OrdManualPriceAdjustment extends BaseEntity {
    private String adjustmentNo;
    private String entityNo;
    private String storeNo;
    private String actorNo;
    private String subjectNo;
    private String manualKind;
    private Long requestedMinor;
    private Integer payableRateBp;
    private String currency;
    private Long discountMinor;
    private String reasonCode;
    private String reason;
    private String sourceJson;
    private String preparationHash;
    private String configHash;
    private String taxHash;
    private String policyNo;
    private Long policyVersion;
    private String operationKey;
    private String requestHash;
    private String status;
    private String approvalMode;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime evaluatedAt;
    private LocalDateTime expiresAt;
}
