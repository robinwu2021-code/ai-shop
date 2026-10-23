package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Pricing publication evidence uses business numbers; immutable revisions are insert/query only. */
@Getter
@Setter
@TableName("ord_pricing_template_revision")
public class OrdPricingTemplateRevision extends BaseEntity {
    private String revisionNo;
    private String templateNo;
    private Long revision;
    private String industryCode;
    private Integer schemaVersion;
    private String definitionSnapshot;
    private String definitionHash;
    private String engineSpecVersion;
    private String ruleCatalogVersion;
    private String previousRevisionNo;
    private LocalDateTime publishedAt;
    private String publishedBy;
    private String publishReason;
}
