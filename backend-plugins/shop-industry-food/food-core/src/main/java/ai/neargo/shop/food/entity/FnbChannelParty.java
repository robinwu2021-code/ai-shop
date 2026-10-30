package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original external restaurant relationship; it grants neither consumer-account access nor local
 * payment authority.
 */
@Getter
@Setter
@TableName("fnb_channel_party")
public class FnbChannelParty extends BaseEntity {
    private String partyNo;
    private String entityNo;
    private String storeNo;
    private String channelStoreKey;
    private String partyKey;
    private String externalCustomerRef;
    private String originalExternalOrderRef;
    private String displayName;
}
