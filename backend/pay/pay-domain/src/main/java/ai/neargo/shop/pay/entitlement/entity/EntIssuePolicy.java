package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Lifetime issuance facts are keyed by template, never reset by publication. */
@Getter
@Setter
@TableName("ent_issue_policy")
public class EntIssuePolicy extends BaseEntity {
    private String templateNo;
    private Long limitCount;
    private Long heldCount;
    private Long issuedCount;
    private Long maxExposureMinor;
    private Long heldExposureMinor;
    private Long usedExposureMinor;
    private Integer perUserLimit;
    private Integer enabled;
}
