package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Restaurant-owned menu scope; external references never carry credentials. */
@Getter
@Setter
@TableName("fnb_menu_context")
public class FnbMenuContext extends BaseEntity {
    private String entityNo;
    private String contextNo;
    private String menuNo;
    private String orderSource;
    private String fulfillment;
    private String channelBindingNo;
    private String channelScopeKey;
    private String pricePolicyRef;
}
