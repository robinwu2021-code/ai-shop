package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntPeriodBucket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bound to the pay SqlSessionFactory only. */
public interface EntPeriodBucketMapper extends BaseMapper<EntPeriodBucket> {
    @Select("""
            SELECT * FROM ent_period_bucket WHERE tenant_no=#{tenant} AND instance_no=#{instance}
            AND period_key=#{period} AND service_day=#{day} AND deleted=0 FOR UPDATE
            """)
    EntPeriodBucket lock(@Param("tenant") String tenant, @Param("instance") String instance,
                        @Param("period") String period, @Param("day") LocalDate day);
}
