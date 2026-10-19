package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.PayEntSourceFence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Source and operation uniqueness are both tenant/purpose scoped. */
public interface PayEntSourceFenceMapper extends BaseMapper<PayEntSourceFence> {
    @Insert("""
            INSERT INTO pay_ent_source_fence(tenant_no,purpose,source_key,operation_no,source_snapshot,
                user_no,entity_no,status,version,deleted,created_at,updated_at,created_by,updated_by)
            VALUES (#{r.tenantNo},#{r.purpose},#{r.sourceKey},#{r.operationNo},#{r.sourceSnapshot},
                #{r.userNo},#{r.entityNo},'OPEN',0,0,#{r.createdAt},#{r.updatedAt},#{r.createdBy},#{r.updatedBy})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int claim(@Param("r") PayEntSourceFence row);

    @Select("""
            SELECT * FROM pay_ent_source_fence WHERE tenant_no=#{tenant} AND purpose=#{purpose}
            AND operation_no=#{no} AND deleted=0 FOR UPDATE
            """)
    PayEntSourceFence lock(@Param("tenant") String tenant, @Param("purpose") String purpose, @Param("no") String no);

    @Select("""
            SELECT * FROM pay_ent_source_fence WHERE tenant_no=#{tenant} AND purpose=#{purpose}
            AND operation_no=#{no} AND deleted=0
            """)
    PayEntSourceFence find(@Param("tenant") String tenant, @Param("purpose") String purpose, @Param("no") String no);
}
