package ai.neargo.commerce.persistence.trade;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallRefundItemRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MallRefundItemRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MallRefundItemRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mall_refund_item` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`refund_item_no`,`refund_no`,`order_component_no`,`period_no`,`quantity`,`cash_refund_minor`,`credit_restore_minor`,`rights_action`,`status`,`adjustment_snapshot`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.refundItemNo(),row.refundNo(),row.orderComponentNo(),row.periodNo(),row.quantity(),row.cashRefundMinor(),row.creditRestoreMinor(),row.rightsAction(),row.status(),row.adjustmentSnapshot()); }
 public Optional<MallRefundItemRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mall_refund_item` WHERE tenant_no=? AND id=?",(rs,n)->new MallRefundItemRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("refund_item_no"),rs.getString("refund_no"),rs.getString("order_component_no"),rs.getString("period_no"),rs.getObject("quantity", Integer.class),rs.getObject("cash_refund_minor", Long.class),rs.getObject("credit_restore_minor", Long.class),rs.getString("rights_action"),rs.getString("status"),rs.getString("adjustment_snapshot")),tenantNo,id).stream().findFirst(); }
}
