package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bound to the pay SqlSessionFactory only. */
public interface EntUsageMapper extends BaseMapper<EntUsage> {
    @Select("""
            SELECT DISTINCT u.* FROM ent_usage u JOIN ent_use_allocation a
            ON a.tenant_no=u.tenant_no AND a.operation_no=u.operation_no
            WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.entity_no=#{entity}
            AND a.instance_no=#{instance} AND (#{store} IS NULL OR u.store_no=#{store})
            AND u.deleted=0 AND a.deleted=0
            ORDER BY u.occurred_at DESC,u.usage_no DESC LIMIT 20
            """)
    List<EntUsage> recent(@Param("tenant") String tenant, @Param("user") String user,
                          @Param("entity") String entity, @Param("instance") String instance,
                                  @Param("store") String store);
}
