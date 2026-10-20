package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.migration.PmtMigrationDecision;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LegacyCouponSourceCommitMapper extends BaseMapper<PmtMigrationDecision> {
    @Select("SELECT * FROM pmt_migration_decision WHERE tenant_no=#{tenant} AND plan_no=#{plan} AND deleted=0")
    PmtMigrationDecision findPlan(@Param("tenant") String tenant,@Param("plan") String plan);
    @Select("SELECT d.* FROM pmt_migration_decision d JOIN pmt_migration_source s ON s.tenant_no=d.tenant_no AND s.decision_no=d.decision_no "
            +"WHERE s.tenant_no=#{tenant} AND s.source_system=#{system} AND s.source_template_no=#{source} AND s.deleted=0 AND d.deleted=0")
    PmtMigrationDecision findSource(@Param("tenant") String tenant,@Param("system") String system,@Param("source") String source);
    @Insert("""
            INSERT INTO pmt_migration_source(tenant_no,source_system,source_template_no,decision_no,version,deleted,
            created_at,updated_at,created_by,updated_by)
            VALUES(#{decision.tenantNo},#{system},#{source},#{decision.decisionNo},0,0,
            #{decision.createdAt},#{decision.updatedAt},#{decision.createdBy},#{decision.updatedBy})
            """)
    int bind(@Param("decision") PmtMigrationDecision decision,@Param("system") String system,@Param("source") String source);
}
