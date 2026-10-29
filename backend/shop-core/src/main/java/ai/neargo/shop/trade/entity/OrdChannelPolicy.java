package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Original channel admission bookkeeping; collection evidence never marks a local cash payment. */
@Getter
@Setter
@TableName("ord_channel_policy")
public class OrdChannelPolicy extends BaseEntity {
    private String policyNo;
    private String entityNo;
    private String storeNo;
    private String channelStoreKey;
    private String currency;
    private Boolean enabled;
    private Boolean allowPlatformCollected;
    private Boolean allowCashOnDelivery;
    private Long maxSingleMinor;
    private Long maxOutstandingMinor;
    private Long reservedMinor;
    private Integer holdSeconds;
}
