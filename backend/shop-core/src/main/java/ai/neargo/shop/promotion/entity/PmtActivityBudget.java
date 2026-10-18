package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_activity_budget")
public class PmtActivityBudget extends BaseEntity {
    private String activityNo;
    private String currency;
    private Long quotaLimit;
    private Long quotaHeld;
    private Long quotaUsed;
    private Long budgetLimitMinor;
    private Long budgetHeldMinor;
    private Long budgetUsedMinor;
    private Integer perUserLimit;
}
