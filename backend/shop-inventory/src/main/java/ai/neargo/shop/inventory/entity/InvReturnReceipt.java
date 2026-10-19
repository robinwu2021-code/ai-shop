package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable proof of a physically received return line and its quality disposition. */
@Getter
@Setter
@TableName("inv_return_receipt")
public class InvReturnReceipt extends InvEntity {
    private String receiptNo;
    private Integer lineNo;
    private String afterSaleNo;
    private String originalOrderNo;
    private String fulfillmentRef;
    private String requestHash;
    private Long originalLineId;
    private String ownerId;
    private String itemId;
    private Integer qty;
    private String quality;
    private String locationId;
    private String inboundNo;
}
