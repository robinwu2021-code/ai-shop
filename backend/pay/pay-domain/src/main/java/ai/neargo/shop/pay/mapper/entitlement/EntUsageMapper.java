package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bound to the pay SqlSessionFactory only. */
public interface EntUsageMapper extends BaseMapper<EntUsage> {
    @Select("SELECT * FROM ent_usage WHERE tenant_no=#{tenant} AND user_no=#{user} AND entity_no=#{entity} "
            + "AND store_no=#{store} AND asset_kind='STORED_VALUE' AND status='COMMITTED' AND deleted=0 "
            + "ORDER BY occurred_at DESC,usage_no DESC LIMIT #{limit}")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,
            useCache = false)
    List<EntUsage> firstStoredValuePage(@Param("tenant") String tenant, @Param("user") String user,
            @Param("entity") String entity, @Param("store") String store, @Param("limit") int limit);

    @Select("SELECT * FROM ent_usage WHERE tenant_no=#{tenant} AND user_no=#{user} AND entity_no=#{entity} "
            + "AND store_no=#{store} AND asset_kind='STORED_VALUE' AND status='COMMITTED' AND deleted=0 "
            + "AND (occurred_at<#{afterAt} OR (occurred_at=#{afterAt} AND usage_no<#{afterUsage})) "
            + "ORDER BY occurred_at DESC,usage_no DESC LIMIT #{limit}")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,
            useCache = false)
    List<EntUsage> storedValuePage(@Param("tenant") String tenant, @Param("user") String user,
            @Param("entity") String entity, @Param("store") String store,
            @Param("afterAt") LocalDateTime afterAt, @Param("afterUsage") String afterUsage,
            @Param("limit") int limit);

    @Select("""
            SELECT DISTINCT u.* FROM ent_usage u LEFT JOIN ent_use_allocation a
            ON a.tenant_no=u.tenant_no AND a.operation_no=u.operation_no AND u.protocol='STANDARD' AND a.deleted=0
            WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.entity_no=#{entity}
            AND ((u.protocol='STANDARD' AND a.instance_no=#{instance})
                 OR (u.protocol IN ('LEGACY','MIGRATED') AND u.legacy_instance_no=#{instance}))
            AND (#{store} IS NULL OR u.store_no=#{store})
            AND u.deleted=0
            ORDER BY u.occurred_at DESC,u.usage_no DESC LIMIT 20
            """)
    List<EntUsage> recent(@Param("tenant") String tenant, @Param("user") String user,
                          @Param("entity") String entity, @Param("instance") String instance,
                                  @Param("store") String store);

    @Select("""
            SELECT u.* FROM ent_usage u
            WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND (#{entity} IS NULL OR u.entity_no=#{entity}) AND u.deleted=0
            AND (#{instance} IS NULL OR (u.protocol IN ('LEGACY','MIGRATED') AND u.legacy_instance_no=#{instance})
              OR (u.protocol='STANDARD' AND EXISTS (SELECT 1 FROM ent_use_allocation a
                  WHERE a.tenant_no=u.tenant_no AND a.operation_no=u.operation_no AND a.instance_no=#{instance} AND a.deleted=0)))
            AND (#{afterAt} IS NULL OR u.occurred_at<#{afterAt} OR (u.occurred_at=#{afterAt} AND u.usage_no<#{afterUsage}))
            ORDER BY u.occurred_at DESC,u.usage_no DESC LIMIT #{limit}
            """)
    List<EntUsage> history(@Param("tenant") String tenant,@Param("user") String user,@Param("entity") String entity,
            @Param("instance") String instance,@Param("afterAt") LocalDateTime afterAt,
            @Param("afterUsage") String afterUsage,@Param("limit") int limit);
}
