package ai.neargo.commerce.persistence.trade;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallFulfillmentTaskRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MallFulfillmentTaskRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MallFulfillmentTaskRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mall_fulfillment_task` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`task_no`,`order_component_no`,`unit_seq`,`task_type`,`status`,`attempt_count`,`next_retry_at`,`result_no`,`last_error_code`,`lease_until`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.taskNo(),row.orderComponentNo(),row.unitSeq(),row.taskType(),row.status(),row.attemptCount(),row.nextRetryAt(),row.resultNo(),row.lastErrorCode(),row.leaseUntil()); }
 public Optional<MallFulfillmentTaskRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mall_fulfillment_task` WHERE tenant_no=? AND id=?",(rs,n)->new MallFulfillmentTaskRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("task_no"),rs.getString("order_component_no"),rs.getObject("unit_seq", Integer.class),rs.getString("task_type"),rs.getString("status"),rs.getObject("attempt_count", Integer.class),rs.getObject("next_retry_at", Long.class),rs.getString("result_no"),rs.getString("last_error_code"),rs.getObject("lease_until", Long.class)),tenantNo,id).stream().findFirst(); }
}
