package ai.neargo.commerce.persistence.catalog;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class SysIndustryPlanVersionRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public SysIndustryPlanVersionRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(SysIndustryPlanVersionRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `sys_industry_plan_version` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`plan_version_no`,`plan_no`,`revision`,`display_name`,`summary`,`status`,`policy_version`,`published_at`,`published_by`,`change_note`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.planVersionNo(),row.planNo(),row.revision(),row.displayName(),row.summary(),row.status(),row.policyVersion(),row.publishedAt(),row.publishedBy(),row.changeNote()); }
 public Optional<SysIndustryPlanVersionRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `sys_industry_plan_version` WHERE tenant_no=? AND id=?",(rs,n)->new SysIndustryPlanVersionRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("plan_version_no"),rs.getString("plan_no"),rs.getObject("revision", Integer.class),rs.getString("display_name"),rs.getString("summary"),rs.getString("status"),rs.getString("policy_version"),rs.getObject("published_at", Long.class),rs.getString("published_by"),rs.getString("change_note")),tenantNo,id).stream().findFirst(); }
}
