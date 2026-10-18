package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Payment-owned catalog row; published snapshots are never rewritten or deleted. */
@Getter
@Setter
@TableName("ent_template_version")
public class EntTemplateVersion extends BaseEntity {
    private String versionNo;
    private String templateNo;
    private Integer versionIndex;
    private String currency;
    private String benefitMode;
    private Long benefitValue;
    private Long benefitCapMinor;
    private Long minAmountMinor;
    private String usageModel;
    private Integer totalUnits;
    private Integer dailyUnits;
    private String validityMode;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private Integer validDays;
    private String startBasis;
    private String periodBasis;
    private String marketZone;
    private String redemptionScope;
    private String allowedIssueModes;
    private String policySnapshot;
    private String snapshotHash;
    private String publishedBy;
    private LocalDateTime publishedAt;
}
