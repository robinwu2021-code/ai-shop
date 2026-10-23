package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Pricing publication evidence uses business numbers; immutable revisions are insert/query only. */
@Getter
@Setter
@TableName("ord_store_pricing_revision")
public class OrdStorePricingRevision extends BaseEntity {
    private String revisionNo;
    private String configNo;
    private Long revision;
    private String entityNo;
    private String storeNo;
    private String industryCode;
    private String templateRevisionNo;
    private String overrideSnapshot;
    private String effectiveSnapshot;
    private Integer schemaVersion;
    private String effectiveHash;
    private String engineSpecVersion;
    private String ruleCatalogVersion;
    private String previousRevisionNo;
    private String rollbackFromRevisionNo;
    private LocalDateTime publishedAt;
    private String publishedBy;
    private String publishReason;
}
