package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Original channel admission bookkeeping; collection evidence never marks a local cash payment. */
@Getter
@Setter
@TableName("ord_channel_quote")
public class OrdChannelQuote extends BaseEntity {
    private String quoteNo;
    private String entityNo;
    private String storeNo;
    private String admissionNo;
    private String sourceSnapshot;
    private String sourceHash;
    private String currency;
    private Long merchantReceivableMinor;
    private Long customerPayableMinor;
    private String sourceReceiptRef;
    private String retainedOrderNo;
}
