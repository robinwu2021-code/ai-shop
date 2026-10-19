package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntGrant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Lock the stable grant/source claim; duplicate source with another operation number must never issue twice. */
public interface EntGrantMapper extends BaseMapper<EntGrant> {
    @Select("SELECT * FROM ent_grant WHERE tenant_no=#{tenant} AND grant_no=#{no} AND deleted=0")
    EntGrant find(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM ent_grant WHERE tenant_no=#{tenant} AND grant_no=#{no} AND deleted=0 FOR UPDATE")
    EntGrant lock(@Param("tenant") String tenant, @Param("no") String no);
    @Insert("INSERT INTO ent_grant (tenant_no,grant_no,source_key,source_snapshot,template_version_no,user_no,"
            + "entity_no,reservation_no,paid_fact_ref,quantity,payload_hash,status,prepared_snapshot,version,deleted,"
            + "created_at,updated_at,created_by,updated_by) VALUES (#{tenantNo},#{grantNo},#{sourceKey},"
            + "#{sourceSnapshot},#{templateVersionNo},#{userNo},#{entityNo},#{reservationNo},#{paidFactRef},"
            + "#{quantity},#{payloadHash},'PREPARING',#{preparedSnapshot},0,0,#{createdAt},#{updatedAt},"
            + "#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id")
    int claim(EntGrant row);
}
