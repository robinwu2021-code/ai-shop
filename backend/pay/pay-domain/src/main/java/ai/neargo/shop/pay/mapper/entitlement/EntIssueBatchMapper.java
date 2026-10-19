package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntIssueBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Serialize one recipient result and its grant while keeping different batches independent. */
public interface EntIssueBatchMapper extends BaseMapper<EntIssueBatch> {
    @Select("SELECT * FROM ent_issue_batch WHERE tenant_no=#{tenant} AND entity_no=#{entity} "
            + "AND batch_no=#{no} AND deleted=0")
    EntIssueBatch find(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no);
    @Select("SELECT * FROM ent_issue_batch WHERE tenant_no=#{tenant} AND entity_no=#{entity} "
            + "AND batch_no=#{no} AND deleted=0 FOR UPDATE")
    EntIssueBatch lock(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no);
}
