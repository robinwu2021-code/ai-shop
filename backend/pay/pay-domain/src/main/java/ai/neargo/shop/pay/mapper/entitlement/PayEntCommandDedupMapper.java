package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.PayEntCommandDedup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Claim and replay use an exclusive lock, avoiding duplicate-insert shared-lock upgrades in InnoDB. */
public interface PayEntCommandDedupMapper extends BaseMapper<PayEntCommandDedup> {
    /** No-op duplicate update obtains an exclusive lock without rewriting the original command. */
    @Insert("INSERT INTO pay_ent_command_dedup (tenant_no,operation_kind,request_key,payload_hash,resource_no,"
            + "result_status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES "
            + "(#{tenantNo},#{operationKind},#{requestKey},#{payloadHash},#{resourceNo},'PENDING',0,0,"
            + "#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id")
    int claim(PayEntCommandDedup command);

    /** A retry can replay only a complete row committed by the original transaction. */
    @Select("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=#{tenant} AND operation_kind=#{operation} "
            + "AND request_key=#{key} AND deleted=0 FOR UPDATE")
    PayEntCommandDedup lock(@Param("tenant") String tenant, @Param("operation") String operation,
                           @Param("key") String key);

    @Select("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=#{tenant} AND operation_kind=#{operation} "
            + "AND request_key=#{key} AND deleted=0")
    PayEntCommandDedup find(@Param("tenant") String tenant, @Param("operation") String operation,
                           @Param("key") String key);

    /** Finish the stable response inside the same pay transaction as the business mutation. */
    @Update("UPDATE pay_ent_command_dedup SET result_status='COMPLETED',response_snapshot=#{response}, "
            + "updated_by=#{actor},updated_at=#{at},version=version+1 "
            + "WHERE id=#{id} AND tenant_no=#{tenant} AND result_status='PENDING' AND deleted=0")
    int complete(@Param("id") long id, @Param("tenant") String tenant, @Param("response") String response,
                 @Param("actor") String actor, @Param("at") LocalDateTime at);
}
