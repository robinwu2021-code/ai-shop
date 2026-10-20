package ai.neargo.shop.pay.mapper.entitlement;
import ai.neargo.shop.pay.entitlement.entity.PayEntReconRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
public interface PayEntReconRunMapper extends BaseMapper<PayEntReconRun> {
    @Insert("INSERT INTO pay_ent_recon_run(tenant_no,recon_no,request_key,payload_hash,entity_no,axis,currency,biz_date,market_zone,"
            + "status,checked_count,diff_count,started_at,version,deleted,created_at,updated_at,created_by,updated_by) "
            + "VALUES(#{tenantNo},#{reconNo},#{requestKey},#{payloadHash},#{entityNo},#{axis},#{currency},#{bizDate},#{marketZone},"
            + "'RUNNING',0,0,#{startedAt},0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id")
    int claim(PayEntReconRun run);
    @Select("SELECT * FROM pay_ent_recon_run WHERE tenant_no=#{tenant} AND request_key=#{key} AND deleted=0 FOR UPDATE")
    PayEntReconRun lock(@Param("tenant") String tenant,@Param("key") String key);
}
