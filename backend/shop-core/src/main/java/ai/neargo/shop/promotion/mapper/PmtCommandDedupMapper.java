package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtCommandDedup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Duplicate claims take an exclusive lock; a response commits with its activity mutation. */
public interface PmtCommandDedupMapper extends BaseMapper<PmtCommandDedup> {
    @Insert("INSERT INTO pmt_command_dedup (tenant_no,operation_kind,request_key,payload_hash,resource_no,"
            + "result_status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES "
            + "(#{tenantNo},#{operationKind},#{requestKey},#{payloadHash},#{resourceNo},'PENDING',0,0,"
            + "#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id")
    int claim(PmtCommandDedup row);

    @Select("SELECT * FROM pmt_command_dedup WHERE tenant_no=#{tenant} AND operation_kind=#{operation} "
            + "AND request_key=#{key} AND deleted=0 FOR UPDATE")
    PmtCommandDedup lock(@Param("tenant") String tenant, @Param("operation") String operation,
                        @Param("key") String key);

    @Update("UPDATE pmt_command_dedup SET result_status='COMPLETED',response_snapshot=#{response},"
            + "updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND tenant_no=#{tenant} "
            + "AND result_status='PENDING' AND deleted=0")
    int complete(@Param("id") long id, @Param("tenant") String tenant, @Param("response") String response);
}
