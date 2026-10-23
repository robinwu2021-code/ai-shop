package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Pricing publication evidence uses business numbers; immutable revisions are insert/query only. */
@Getter
@Setter
@TableName("ord_pricing_template")
public class OrdPricingTemplate extends BaseEntity {
    private String templateNo;
    private String industryCode;
    private String templateCode;
    private String name;
    private String status;
    private String activeRevisionNo;
    private String draftContent;
    private Integer draftSchemaVersion;
    private Long draftVersion;
    /** Immutable import provenance survives receipt expiry; draft editing never replaces it. */
    private String seedVersion;
    private String seedHash;
    private String seedSnapshot;
}
