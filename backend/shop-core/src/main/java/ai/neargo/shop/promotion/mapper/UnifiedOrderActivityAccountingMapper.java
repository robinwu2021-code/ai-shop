package ai.neargo.shop.promotion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Cancellation returns only previously consumed order capacity, under the activity budget lock. */
@Mapper
public interface UnifiedOrderActivityAccountingMapper {
    @Update("UPDATE pmt_activity_budget SET quota_used=quota_used-1,budget_used_minor=budget_used_minor-#{amount},version=version+1 WHERE tenant_no=#{tenant} AND activity_no=#{activity} AND deleted=0 AND quota_used>=1 AND budget_used_minor>=#{amount}")
    int reverseBudget(@Param("tenant") String tenant, @Param("activity") String activity, @Param("amount") long amount);

    @Update("UPDATE pmt_activity_participant SET used_count=used_count-1,version=version+1 WHERE tenant_no=#{tenant} AND activity_no=#{activity} AND user_no=#{user} AND deleted=0 AND used_count>=1")
    int reverseParticipant(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user);
}
