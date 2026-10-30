package ai.neargo.shop.retail.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A channel transaction party has its own native identity without creating a local user account.
 */
@Getter
@Setter
@TableName("retail_channel_party")
public class RetailChannelParty extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String partyNo;
    private String channelStoreKey;
    private String partyKey;
    private String externalCustomerRef;
    private String externalOrderRef;
    private String displayName;
}
