package ai.neargo.commerce.persistence.catalog;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class SysIndustryPlanRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public SysIndustryPlanRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(SysIndustryPlanRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `sys_industry_plan` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`plan_no`,`business_type_code`,`tier_code`,`rank`,`current_version_no`,`sale_status`,`sort`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.planNo(),row.businessTypeCode(),row.tierCode(),row.rank(),row.currentVersionNo(),row.saleStatus(),row.sort()); }
 public Optional<SysIndustryPlanRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `sys_industry_plan` WHERE tenant_no=? AND id=?",(rs,n)->new SysIndustryPlanRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("plan_no"),rs.getString("business_type_code"),rs.getString("tier_code"),rs.getObject("rank", Integer.class),rs.getString("current_version_no"),rs.getString("sale_status"),rs.getObject("sort", Integer.class)),tenantNo,id).stream().findFirst(); }
}
