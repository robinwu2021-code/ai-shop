package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Original committed fulfillment references; supplier acknowledgement is independent of local
 * business state.
 */
@Getter
@Setter
@TableName("cim_fulfillment_line_link")
public class CimFulfillmentLineLink extends BaseEntity {
    private String linkNo;
    private String entityNo;
    private String fulfillmentLinkNo;
    private String orderLineLinkNo;
    private BigDecimal quantity;
    private String unit;
    private String sourceReceiptRef;
}
