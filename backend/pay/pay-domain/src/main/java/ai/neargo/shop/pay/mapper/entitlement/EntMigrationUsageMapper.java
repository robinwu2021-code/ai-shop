package ai.neargo.shop.pay.mapper.entitlement;
import ai.neargo.shop.pay.migration.entity.EntMigrationUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
public interface EntMigrationUsageMapper extends BaseMapper<EntMigrationUsage> {
    @Select("SELECT * FROM ent_migration_usage WHERE tenant_no=#{tenant} AND source_usage_no=#{source} AND deleted=0 FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    EntMigrationUsage lock(@Param("tenant") String tenant,@Param("source") String source);
}
