package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.migration.entity.EntMigrationAlias;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntMigrationAliasMapper extends BaseMapper<EntMigrationAlias> {
    @Select("SELECT * FROM ent_migration_alias WHERE tenant_no=#{tenant} AND deleted=0 "
            +"AND (pmt_stage_no=#{stage} OR mkt_stage_no=#{stage})")
    EntMigrationAlias findMember(@Param("tenant") String tenant,@Param("stage") String stage);
    @Select("SELECT * FROM ent_migration_alias WHERE tenant_no=#{tenant} AND alias_no=#{no} AND deleted=0 FOR UPDATE")
    @org.apache.ibatis.annotations.Options(flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE,useCache=false)
    EntMigrationAlias lockAlias(@Param("tenant") String tenant,@Param("no") String no);
}
