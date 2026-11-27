package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_quota")
public class PmtActivityBudget extends BaseEntity {
    private String quotaNo;
    private String targetType;
    private String targetNo;
    private String quotaDimension;
    private String activityNo;
    private String currency;
    private Long quotaLimit;
    private Long quotaHeld;
    private Long quotaUsed;
    private Long budgetLimitMinor;
    private Long budgetHeldMinor;
    private Long budgetUsedMinor;
    private Integer perUserLimit;

    public static PmtActivityBudget forActivity(String activityNo) {
        var row = new PmtActivityBudget();
        row.setQuotaNo(UUID.randomUUID().toString());
        row.setTargetType("ACTIVITY");
        row.setTargetNo(activityNo);
        row.setActivityNo(activityNo);
        row.setQuotaDimension("TOTAL");
        return row;
    }
}
