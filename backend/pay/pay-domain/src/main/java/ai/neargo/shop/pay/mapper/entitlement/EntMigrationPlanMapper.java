package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.migration.entity.EntMigrationPlan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntMigrationPlanMapper extends BaseMapper<EntMigrationPlan> {
    @Select("SELECT * FROM ent_migration_plan WHERE tenant_no=#{tenant} AND plan_no=#{no} AND deleted=0 FOR UPDATE")
    @org.apache.ibatis.annotations.Options(flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,useCache=false)
    EntMigrationPlan lock(@Param("tenant") String tenant,@Param("no") String no);
    @Select("""
            SELECT p.* FROM ent_migration_plan p LEFT JOIN ent_migration_import i
              ON i.tenant_no=p.tenant_no AND i.plan_no=p.plan_no AND i.deleted=0
            WHERE p.tenant_no=#{tenant} AND p.deleted=0 AND p.status='PLANNED' AND p.id>#{afterId} AND i.id IS NULL
            ORDER BY p.id LIMIT #{limit}
            """)
    java.util.List<EntMigrationPlan> pendingImport(@Param("tenant") String tenant,@Param("afterId") long afterId,@Param("limit") int limit);
    @Insert("""
            INSERT INTO ent_migration_plan(tenant_no,plan_no,stage_no,alias_no,source_system,source_template_no,migration_batch_no,
            entity_no,request_key,command_hash,reason,plan_hash,source_bundle_hash,crypto_key_id,payload_cipher,payload_bytes,
            asset_count,archival_count,version_count,assessed_at,status,version,deleted,created_at,updated_at,created_by,updated_by)
            VALUES(#{tenantNo},#{planNo},#{stageNo},#{aliasNo},#{sourceSystem},#{sourceTemplateNo},#{migrationBatchNo},
            #{entityNo},#{requestKey},#{commandHash},#{reason},#{planHash},#{sourceBundleHash},#{cryptoKeyId},#{payloadCipher},#{payloadBytes},
            #{assetCount},#{archivalCount},#{versionCount},#{assessedAt},'PLANNED',0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int claim(EntMigrationPlan row);
}
