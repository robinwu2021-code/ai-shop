package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Budget deltas are conditional and must commit with the original reservation transition. */
public interface PmtActivityBudgetMapper extends BaseMapper<PmtActivityBudget> {
    @Select("SELECT * FROM pmt_activity_budget WHERE tenant_no=#{tenant} AND activity_no=#{activity} "
            + "AND deleted=0 FOR UPDATE")
    PmtActivityBudget lock(@Param("tenant") String tenant, @Param("activity") String activity);

    @Update("UPDATE pmt_activity_budget SET quota_held=quota_held+#{quantity},"
            + "budget_held_minor=budget_held_minor+#{amount},version=version+1,updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE tenant_no=#{tenant} AND activity_no=#{activity} AND deleted=0 "
            + "AND #{quantity}>0 AND #{amount}>=0 "
            + "AND (quota_limit IS NULL OR quota_limit-quota_used-quota_held>=#{quantity}) "
            + "AND (budget_limit_minor IS NULL OR budget_limit_minor-budget_used_minor-budget_held_minor>=#{amount})")
    int reserve(@Param("tenant") String tenant, @Param("activity") String activity,
                @Param("quantity") int quantity, @Param("amount") long amount);

    @Update("UPDATE pmt_activity_budget SET quota_held=quota_held-#{quantity},"
            + "budget_held_minor=budget_held_minor-#{amount},quota_used=quota_used+#{usedQuantity},"
            + "budget_used_minor=budget_used_minor+#{usedAmount},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_no=#{tenant} AND activity_no=#{activity} "
            + "AND deleted=0 AND #{quantity}>0 AND #{amount}>=0 "
            + "AND quota_held>=#{quantity} AND budget_held_minor>=#{amount} "
            + "AND ((#{usedQuantity}=0 AND #{usedAmount}=0) "
            + "OR (#{usedQuantity}=#{quantity} AND #{usedAmount}=#{amount}))")
    int settle(@Param("tenant") String tenant, @Param("activity") String activity,
               @Param("quantity") int quantity, @Param("amount") long amount,
               @Param("usedQuantity") int usedQuantity, @Param("usedAmount") long usedAmount);
}
