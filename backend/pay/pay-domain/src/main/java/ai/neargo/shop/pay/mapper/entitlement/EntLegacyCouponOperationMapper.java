package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.migration.entity.EntLegacyCouponOperation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntLegacyCouponOperationMapper extends BaseMapper<EntLegacyCouponOperation> {
    @Select("SELECT * FROM ent_legacy_coupon_operation WHERE tenant_no=#{tenant} AND operation_no=#{no} AND deleted=0")
    EntLegacyCouponOperation find(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM ent_legacy_coupon_operation WHERE tenant_no=#{tenant} AND operation_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    EntLegacyCouponOperation lock(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT source_committed_at FROM ent_legacy_coupon_operation WHERE tenant_no=#{tenant} AND user_no=#{user} "
            +"AND entity_no=#{entity} AND instance_no=#{instance} AND status='COMMITTED' AND deleted=0 "
            +"ORDER BY source_committed_at DESC,operation_no DESC LIMIT 1")
    java.time.LocalDateTime lastCommittedAt(@Param("tenant") String tenant,@Param("user") String user,
            @Param("entity") String entity,@Param("instance") String instance);
    @Select("""
            SELECT o.* FROM ent_legacy_coupon_operation o LEFT JOIN ent_usage u
              ON u.tenant_no=o.tenant_no AND u.operation_no=o.operation_no AND u.protocol='LEGACY' AND u.deleted=0
            WHERE o.tenant_no=#{tenant} AND o.deleted=0 AND o.id>#{afterId}
              AND o.status IN ('COMMITTED','REVERSED') AND u.id IS NULL
            ORDER BY o.id LIMIT #{limit}
            """)
    java.util.List<EntLegacyCouponOperation> missingUsage(@Param("tenant") String tenant,@Param("afterId") long afterId,
            @Param("limit") int limit);
    @Insert("""
            INSERT INTO ent_legacy_coupon_operation(tenant_no,operation_no,checkout_no,origin_system,origin_no,instance_no,
            user_no,entity_no,currency,owner_batch_no,owner_plan_hash,economic_hash,command_hash,source_version,source_snapshot,
            context_snapshot,status,ever_held,version,deleted,created_at,updated_at,created_by,updated_by)
            VALUES(#{tenantNo},#{operationNo},#{checkoutNo},#{originSystem},#{originNo},#{instanceNo},
            #{userNo},#{entityNo},#{currency},#{ownerBatchNo},#{ownerPlanHash},#{economicHash},#{commandHash},#{sourceVersion},
            #{sourceSnapshot},#{contextSnapshot},'PREPARING',0,0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int claim(EntLegacyCouponOperation row);
}
