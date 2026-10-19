package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntUseIntent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

/** Bound to the pay SqlSessionFactory only. */
public interface EntUseIntentMapper extends BaseMapper<EntUseIntent> {
    @Select("SELECT * FROM ent_use_intent WHERE tenant_no=#{tenant} AND operation_no=#{no} AND deleted=0 FOR UPDATE")
    EntUseIntent lock(@Param("tenant") String tenant, @Param("no") String no);

    @Select("SELECT * FROM ent_use_intent WHERE tenant_no=#{tenant} AND operation_no=#{no} AND deleted=0")
    EntUseIntent find(@Param("tenant") String tenant, @Param("no") String no);

    @Insert("""
            INSERT INTO ent_use_intent(tenant_no,operation_no,source_key,active_source_key,source_snapshot,
                user_no,entity_no,store_no,
                asset_kind,authorization_ref,command_snapshot,payload_hash,status,service_day,expires_at,
                version,deleted,created_at,updated_at,created_by,updated_by)
            VALUES (#{r.tenantNo},#{r.operationNo},#{r.sourceKey},#{r.activeSourceKey},#{r.sourceSnapshot},
                #{r.userNo},#{r.entityNo},
                #{r.storeNo},#{r.assetKind},#{r.authorizationRef},#{r.commandSnapshot},#{r.payloadHash},'PREPARING',
                #{r.serviceDay},#{r.expiresAt},0,0,#{r.createdAt},#{r.updatedAt},#{r.createdBy},#{r.updatedBy})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int claim(@Param("r") EntUseIntent row);
}
