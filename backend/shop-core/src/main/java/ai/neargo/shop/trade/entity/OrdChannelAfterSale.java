package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Native immutable core decision and independent resource progress; no buyer or paid status is
 * fabricated.
 */
@Getter
@Setter
@TableName("ord_channel_after_sale")
public class OrdChannelAfterSale extends BaseEntity {
    private String decisionNo;
    private String entityNo;
    private String storeNo;
    private String admissionNo;
    private String sourceSystem;
    private String orderNo;
    private String subOrderNo;
    private String externalCaseRef;
    private String requestKeyHash;
    private String requestHash;
    private String sourceHash;
    private String decisionSnapshot;
    private String action;
    private Long refundCeilingMinor;
    private String currency;
    private String fundsStatus;
    private String inventoryStatus;
    private String inventoryReceiptSnapshot;
}
