package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /*
     * 以下四个是审批事实，存在轻量审批 sys_approval（TDD-基础能力库对齐 §2.2 1e），不在本表；
     * 读出时由服务从审批单填上（OWNER_ORIGINATED ↔ AUTO、OWNER_REVIEW ↔ MANUAL），供校验与计价快照用。
     */
    @TableField(exist = false)
    private String status;
    @TableField(exist = false)
    private String approvalMode;
    @TableField(exist = false)
    private String approvedBy;
    @TableField(exist = false)
    private LocalDateTime approvedAt;
    private LocalDateTime evaluatedAt;
    private LocalDateTime expiresAt;
}
