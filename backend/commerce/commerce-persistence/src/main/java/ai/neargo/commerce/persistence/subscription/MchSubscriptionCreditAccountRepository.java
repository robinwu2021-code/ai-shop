package ai.neargo.commerce.persistence.subscription;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionCreditAccountRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MchSubscriptionCreditAccountRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MchSubscriptionCreditAccountRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mch_subscription_credit_account` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`store_no`,`currency`,`balance_minor`,`reserved_minor`) VALUES (?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.storeNo(),row.currency(),row.balanceMinor(),row.reservedMinor()); }
 public Optional<MchSubscriptionCreditAccountRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mch_subscription_credit_account` WHERE tenant_no=? AND id=?",(rs,n)->new MchSubscriptionCreditAccountRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("store_no"),rs.getString("currency"),rs.getObject("balance_minor", Long.class),rs.getObject("reserved_minor", Long.class)),tenantNo,id).stream().findFirst(); }
}
