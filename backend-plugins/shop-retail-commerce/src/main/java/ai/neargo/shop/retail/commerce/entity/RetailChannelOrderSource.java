package ai.neargo.shop.retail.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original retail channel invoices and genuine core receipts are retained independently of mutable
 * retail prices.
 */
@Getter
@Setter
@TableName("retail_channel_order_source")
public class RetailChannelOrderSource extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String sourceNo;
    private String importNo;
    private String originalDocumentHash;
    private String financialHash;
    private String channelStoreKey;
    private String externalOrderKey;
    private String externalOrderRef;
    private String partyNo;
    private Long sourceVersion;
    private String sourceHash;
    private String sourceSnapshot;
    private String originSnapshot;
    private String status;
    private String preparationSnapshot;
    private String acceptanceSnapshot;
    private String cancellationSnapshot;
    private String acceptanceReceiptRef;
    private String cancellationReceiptRef;
}
