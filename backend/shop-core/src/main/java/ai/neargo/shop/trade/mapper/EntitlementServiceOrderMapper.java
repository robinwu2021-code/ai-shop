package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
