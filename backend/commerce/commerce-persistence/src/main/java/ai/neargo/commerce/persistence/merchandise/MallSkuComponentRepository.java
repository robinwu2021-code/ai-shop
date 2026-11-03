package ai.neargo.commerce.persistence.merchandise;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallSkuComponentRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MallSkuComponentRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MallSkuComponentRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mall_sku_component` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`component_no`,`sku_version_no`,`component_seq`,`component_type`,`plan_version_no`,`entitlement_code`,`hardware_sku_no`,`period_days`,`unit_count`,`allocation_weight`,`is_gift`,`activation_policy`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.componentNo(),row.skuVersionNo(),row.componentSeq(),row.componentType(),row.planVersionNo(),row.entitlementCode(),row.hardwareSkuNo(),row.periodDays(),row.unitCount(),row.allocationWeight(),row.isGift(),row.activationPolicy()); }
 public Optional<MallSkuComponentRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mall_sku_component` WHERE tenant_no=? AND id=?",(rs,n)->new MallSkuComponentRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("component_no"),rs.getString("sku_version_no"),rs.getObject("component_seq", Integer.class),rs.getString("component_type"),rs.getString("plan_version_no"),rs.getString("entitlement_code"),rs.getString("hardware_sku_no"),rs.getObject("period_days", Integer.class),rs.getObject("unit_count", Integer.class),rs.getObject("allocation_weight", Long.class),rs.getObject("is_gift", Integer.class),rs.getString("activation_policy")),tenantNo,id).stream().findFirst(); }
}
