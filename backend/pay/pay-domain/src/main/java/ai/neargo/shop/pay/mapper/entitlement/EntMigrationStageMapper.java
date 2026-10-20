package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntMigrationStageMapper extends BaseMapper<EntMigrationStage> {
    @Select("SELECT * FROM ent_migration_stage WHERE tenant_no=#{tenant} AND request_key=#{key} AND deleted=0 FOR UPDATE")
    EntMigrationStage lockRequest(@Param("tenant") String tenant,@Param("key") String key);
    @Select("SELECT * FROM ent_migration_stage WHERE tenant_no=#{tenant} AND stage_no=#{no} AND deleted=0 FOR UPDATE")
    @org.apache.ibatis.annotations.Options(flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,useCache=false)
    EntMigrationStage lockStage(@Param("tenant") String tenant,@Param("no") String no);
    @Insert("""
            INSERT INTO ent_migration_stage(tenant_no,stage_no,source_system,source_template_no,migration_batch_no,
            entity_no,request_key,command_hash,reason,source_schema_version,frozen_revision,watermark,content_hash,
            crypto_key_id,payload_cipher,payload_bytes,instance_count,history_count,manifest_version,history_available,status,version,deleted,
            created_at,updated_at,created_by,updated_by) VALUES(#{tenantNo},#{stageNo},#{sourceSystem},#{sourceTemplateNo},
            #{migrationBatchNo},#{entityNo},#{requestKey},#{commandHash},#{reason},#{sourceSchemaVersion},#{frozenRevision},
            #{watermark},#{contentHash},#{cryptoKeyId},#{payloadCipher},#{payloadBytes},#{instanceCount},#{historyCount},
            #{manifestVersion},#{historyAvailable,jdbcType=TINYINT},'STAGED',0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id
            """)
    int claim(EntMigrationStage row);
}
