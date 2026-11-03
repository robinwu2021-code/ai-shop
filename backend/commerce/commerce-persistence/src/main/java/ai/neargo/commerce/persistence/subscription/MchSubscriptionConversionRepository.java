package ai.neargo.commerce.persistence.subscription;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionConversionRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MchSubscriptionConversionRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MchSubscriptionConversionRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mch_subscription_conversion` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`conversion_no`,`store_no`,`order_no`,`valuation_at`,`effective_at`,`new_period_no`,`currency`,`converted_minor`,`credit_consumed_minor`,`cash_paid_minor`,`policy_version`,`status`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.conversionNo(),row.storeNo(),row.orderNo(),row.valuationAt(),row.effectiveAt(),row.newPeriodNo(),row.currency(),row.convertedMinor(),row.creditConsumedMinor(),row.cashPaidMinor(),row.policyVersion(),row.status()); }
 public Optional<MchSubscriptionConversionRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mch_subscription_conversion` WHERE tenant_no=? AND id=?",(rs,n)->new MchSubscriptionConversionRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("conversion_no"),rs.getString("store_no"),rs.getString("order_no"),rs.getObject("valuation_at", Long.class),rs.getObject("effective_at", Long.class),rs.getString("new_period_no"),rs.getString("currency"),rs.getObject("converted_minor", Long.class),rs.getObject("credit_consumed_minor", Long.class),rs.getObject("cash_paid_minor", Long.class),rs.getString("policy_version"),rs.getString("status")),tenantNo,id).stream().findFirst(); }
}
