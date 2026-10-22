package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Pricing publication evidence uses business numbers; immutable revisions are insert/query only. */
@Getter
@Setter
@TableName("ord_store_pricing_config")
public class OrdStorePricingConfig extends BaseEntity {
    private String configNo;
    private String entityNo;
    private String storeNo;
    private String industryCode;
    private String templateNo;
    private String status;
    private String activeRevisionNo;
    private String draftTemplateRevisionNo;
    private String draftOverride;
    private Integer draftSchemaVersion;
    private Long draftVersion;
}
