package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Immutable original owner allocations support partial fulfillment/refunds without reconstructing
 * today's SKU price.
 */
@Getter
@Setter
@TableName("cim_order_line_link")
public class CimOrderLineLink extends BaseEntity {
    private String linkNo;
    private String entityNo;
    private String channelOrderNo;
    private String orderRevisionNo;
    private String externalLineRef;
    private String parentExternalLineRef;
    private String componentKind;
    private String componentRef;
    private String sourceLineRef;
    private String coreOrderRef;
    private String coreItemRef;
    private BigDecimal saleQuantity;
    private String quantityUnit;
    private Long amountMinor;
    private String currency;
    private String allocationEvidenceRef;
}
