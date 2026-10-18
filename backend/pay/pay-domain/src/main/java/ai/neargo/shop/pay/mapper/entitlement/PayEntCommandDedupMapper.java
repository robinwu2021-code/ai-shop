package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.PayEntCommandDedup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** A duplicate insert waits for the original transaction; replay reads only its committed stable response. */
public interface PayEntCommandDedupMapper extends BaseMapper<PayEntCommandDedup> {
    /** A retry can replay only a complete row committed by the original transaction. */
    @Select("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=#{tenant} AND operation_kind=#{operation} "
            + "AND request_key=#{key} AND deleted=0 FOR UPDATE")
    PayEntCommandDedup lock(@Param("tenant") String tenant, @Param("operation") String operation,
                           @Param("key") String key);

    /** Finish the stable response inside the same pay transaction as the business mutation. */
    @Update("UPDATE pay_ent_command_dedup SET result_status='COMPLETED',response_snapshot=#{response}, "
            + "updated_by=#{actor},updated_at=#{at},version=version+1 "
            + "WHERE id=#{id} AND tenant_no=#{tenant} AND result_status='PENDING' AND deleted=0")
    int complete(@Param("id") long id, @Param("tenant") String tenant, @Param("response") String response,
                 @Param("actor") String actor, @Param("at") LocalDateTime at);
}
