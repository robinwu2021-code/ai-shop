package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.migration.entity.EntOwnerRegistry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;

/** Exclusive source claim never overwrites an existing batch, target, owner or migration state. */
public interface EntOwnerRegistryMapper extends BaseMapper<EntOwnerRegistry> {
    @Insert("INSERT INTO ent_owner_registry(tenant_no,source_system,source_kind,source_no,owner_system,"
            + "migration_batch_no,status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES "
            + "(#{tenantNo},#{sourceSystem},#{sourceKind},#{sourceNo},'CORE_LEGACY',#{migrationBatchNo},'PREPARING',"
            + "0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id")
    int claimLegacy(EntOwnerRegistry row);
    @Select("SELECT * FROM ent_owner_registry WHERE tenant_no=#{tenant} AND source_system=#{system} "
            + "AND source_kind=#{kind} AND source_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    EntOwnerRegistry lock(@Param("tenant") String tenant,@Param("system") String system,
            @Param("kind") String kind,@Param("no") String no);
    @org.apache.ibatis.annotations.Update("UPDATE ent_owner_registry SET target_no=#{target},owner_system='PAY',status='CUTOVER',"
            +"cutover_watermark=#{hash},updated_at=#{at},updated_by=#{actor},version=version+1 "
            +"WHERE tenant_no=#{tenant} AND id=#{id} AND version=#{revision} AND owner_system='CORE_LEGACY' "
            +"AND status IN ('PREPARING','VERIFIED') AND migration_batch_no=#{batch} "
            +"AND (target_no IS NULL OR target_no=#{target}) AND (cutover_watermark IS NULL OR cutover_watermark=#{hash}) AND deleted=0")
    int cutover(@Param("tenant") String tenant,@Param("id") long id,@Param("revision") long revision,
            @Param("target") String target,@Param("batch") String batch,@Param("hash") String hash,
            @Param("actor") String actor,@Param("at") java.time.LocalDateTime at);
}
