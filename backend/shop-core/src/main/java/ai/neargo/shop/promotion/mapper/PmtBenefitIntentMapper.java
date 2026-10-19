package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtBenefitIntent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PmtBenefitIntentMapper extends BaseMapper<PmtBenefitIntent> {
    @Select("SELECT * FROM pmt_benefit_intent WHERE tenant_no=#{tenant} AND intent_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    PmtBenefitIntent find(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM pmt_benefit_intent WHERE tenant_no=#{tenant} AND intent_no=#{no} AND deleted=0 FOR UPDATE")
    PmtBenefitIntent lock(@Param("tenant") String tenant, @Param("no") String no);
    @Insert("INSERT IGNORE INTO pmt_benefit_intent(tenant_no,intent_no,user_no,request_key_hash,payload_hash,status,"
            + "plan_snapshot,version,deleted,created_at,updated_at,created_by,updated_by) "
            + "VALUES(#{tenantNo},#{intentNo},"
            + "#{userNo},#{requestKeyHash},#{payloadHash},#{status},#{planSnapshot},0,0,#{createdAt},#{updatedAt},"
            + "#{createdBy},#{updatedBy})")
    int insertIfAbsent(PmtBenefitIntent row);
    @Update("UPDATE pmt_benefit_intent SET status=#{status},plan_snapshot=#{plan},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_no=#{tenant} AND intent_no=#{no} AND deleted=0")
    int save(@Param("tenant") String tenant, @Param("no") String no, @Param("status") String status,
             @Param("plan") String plan);
}
