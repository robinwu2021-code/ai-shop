package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EntitlementServiceOrderMapper extends BaseMapper<OrdEntServiceOrder> {
    @Select("SELECT * FROM ord_ent_service_order WHERE tenant_no=#{tenant} AND service_order_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    OrdEntServiceOrder find(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM ord_ent_service_order WHERE tenant_no=#{tenant} AND operation_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    OrdEntServiceOrder findOperation(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM ord_ent_service_order WHERE tenant_no=#{tenant} AND service_order_no=#{no} "
            + "AND deleted=0 FOR UPDATE")
    OrdEntServiceOrder lock(@Param("tenant") String tenant, @Param("no") String no);
    @Insert("INSERT IGNORE INTO ord_ent_service_order(tenant_no,service_order_no,operation_no,entity_no,store_no,"
            + "user_no,operator_no,request_key_hash,payload_hash,source_snapshot,source_hash,status,context_snapshot,"
            + "deadline,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(#{tenantNo},#{serviceOrderNo},"
            + "#{operationNo},#{entityNo},#{storeNo},#{userNo},#{operatorNo},#{requestKeyHash},#{payloadHash},"
            + "#{sourceSnapshot},#{sourceHash},'CREATED',#{contextSnapshot},#{deadline},0,0,#{createdAt},#{updatedAt},"
            + "#{createdBy},#{updatedBy})")
    int insertIfAbsent(OrdEntServiceOrder row);

    record TaskClaim(String tenantNo, String entityNo, String workOrderNo, String taskNo,
                     String serviceOrderNo) {}

    @Insert("""
            INSERT INTO ord_ent_service_task_claim
                (tenant_no,entity_no,work_order_no,task_no,service_order_no)
            VALUES (#{tenantNo},#{entityNo},#{workOrderNo},#{taskNo},#{serviceOrderNo})
            ON DUPLICATE KEY UPDATE service_order_no=ord_ent_service_task_claim.service_order_no
            """)
    int insertTaskClaim(TaskClaim claim);

    /** Cancellation intent alone never permits another operation to take the task. */
    @Update("""
            UPDATE ord_ent_service_task_claim SET service_order_no=#{serviceOrderNo}
            WHERE tenant_no=#{tenantNo} AND entity_no=#{entityNo}
              AND work_order_no=#{workOrderNo} AND task_no=#{taskNo}
              AND EXISTS (SELECT 1 FROM ord_ent_service_order original
                  WHERE original.tenant_no=ord_ent_service_task_claim.tenant_no
                    AND original.entity_no=ord_ent_service_task_claim.entity_no
                    AND original.service_order_no=ord_ent_service_task_claim.service_order_no
                    AND original.deleted=0 AND original.status='RELEASED')
            """)
    int replaceReleasedTaskClaim(TaskClaim claim);

    @Select("""
            SELECT service_order_no FROM ord_ent_service_task_claim
            WHERE tenant_no=#{tenantNo} AND entity_no=#{entityNo}
              AND work_order_no=#{workOrderNo} AND task_no=#{taskNo} FOR UPDATE
            """)
    String lockTaskClaim(TaskClaim claim);
}
