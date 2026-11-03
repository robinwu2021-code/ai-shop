package ai.neargo.commerce.persistence.subscription;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionReservationRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MchSubscriptionReservationRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MchSubscriptionReservationRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mch_subscription_reservation` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`reservation_no`,`store_no`,`order_no`,`quote_no`,`subscription_version`,`credit_account_version`,`valuation_at`,`pay_deadline`,`status`,`source_periods_snapshot`,`reserved_credit_minor`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.reservationNo(),row.storeNo(),row.orderNo(),row.quoteNo(),row.subscriptionVersion(),row.creditAccountVersion(),row.valuationAt(),row.payDeadline(),row.status(),row.sourcePeriodsSnapshot(),row.reservedCreditMinor()); }
 public Optional<MchSubscriptionReservationRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mch_subscription_reservation` WHERE tenant_no=? AND id=?",(rs,n)->new MchSubscriptionReservationRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("reservation_no"),rs.getString("store_no"),rs.getString("order_no"),rs.getString("quote_no"),rs.getObject("subscription_version", Long.class),rs.getObject("credit_account_version", Long.class),rs.getObject("valuation_at", Long.class),rs.getObject("pay_deadline", Long.class),rs.getString("status"),rs.getString("source_periods_snapshot"),rs.getObject("reserved_credit_minor", Long.class)),tenantNo,id).stream().findFirst(); }
}
