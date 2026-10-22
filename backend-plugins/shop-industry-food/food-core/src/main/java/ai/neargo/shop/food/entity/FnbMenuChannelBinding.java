package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Restaurant-owned menu scope; external references never carry credentials. */
@Getter
@Setter
@TableName("fnb_menu_channel_binding")
public class FnbMenuChannelBinding extends BaseEntity {
    private String entityNo;
    private String bindingNo;
    private String menuNo;
    private String storeNo;
    private String provider;
    private String channelAccountRef;
    private String externalStoreRef;
    private String status;
}
