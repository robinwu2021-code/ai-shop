package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntSaleReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntSaleReservationMapper extends BaseMapper<EntSaleReservation> {
    @Select("SELECT * FROM ent_sale_reservation WHERE tenant_no=#{tenant} AND reservation_no=#{no} AND deleted=0")
    @Options(flushCache=Options.FlushCachePolicy.TRUE, useCache=false)
    EntSaleReservation find(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM ent_sale_reservation WHERE tenant_no=#{tenant} AND source_key=#{key} AND deleted=0")
    @Options(flushCache=Options.FlushCachePolicy.TRUE, useCache=false)
    EntSaleReservation findSource(@Param("tenant") String tenant, @Param("key") String key);
    @Select("SELECT * FROM ent_sale_reservation WHERE tenant_no=#{tenant} AND reservation_no=#{no} AND deleted=0 FOR UPDATE")
    EntSaleReservation lock(@Param("tenant") String tenant, @Param("no") String no);
    @Insert("INSERT INTO ent_sale_reservation(tenant_no,reservation_no,source_key,source_snapshot,command_snapshot,"
            + "payload_hash,template_no,definition_snapshot,user_no,entity_no,currency,status,version,deleted,"
            + "created_at,updated_at,created_by,updated_by) VALUES(#{tenantNo},#{reservationNo},#{sourceKey},"
            + "#{sourceSnapshot},#{commandSnapshot},#{payloadHash},#{templateNo},#{definitionSnapshot},#{userNo},"
            + "#{entityNo},#{currency},'RESERVING',0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) "
            + "ON DUPLICATE KEY UPDATE id=id")
    int claim(EntSaleReservation row);
}
