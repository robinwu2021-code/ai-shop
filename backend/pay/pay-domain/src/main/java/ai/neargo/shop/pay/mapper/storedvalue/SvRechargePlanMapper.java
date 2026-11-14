package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SvRechargePlanMapper extends BaseMapper<SvRechargePlan> {
    // Pay's authenticated caller supplies the business actor. Generic entity update-fill
    // sees the internal service identity and would replace that actor with SYSTEM.
    @Update("""
            UPDATE sv_recharge_plan
               SET status=#{status}, updated_at=#{at}, updated_by=#{actor}, version=version+1
             WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND store_no=#{store}
               AND plan_no=#{plan} AND version=#{revision} AND deleted=0
            """)
    int updateStatus(@Param("tenant") String tenant, @Param("entity") String entity,
            @Param("store") String store, @Param("plan") String plan,
            @Param("status") String status, @Param("revision") long revision,
            @Param("actor") String actor, @Param("at") LocalDateTime at);
}
