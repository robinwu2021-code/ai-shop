package ai.neargo.commerce.persistence.trade;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallShipmentRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MallShipmentRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MallShipmentRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mall_shipment` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`shipment_no`,`order_no`,`shipment_items`,`address_snapshot`,`carrier_code`,`tracking_no`,`status`,`shipped_at`,`signed_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.shipmentNo(),row.orderNo(),row.shipmentItems(),row.addressSnapshot(),row.carrierCode(),row.trackingNo(),row.status(),row.shippedAt(),row.signedAt()); }
 public Optional<MallShipmentRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mall_shipment` WHERE tenant_no=? AND id=?",(rs,n)->new MallShipmentRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("shipment_no"),rs.getString("order_no"),rs.getString("shipment_items"),rs.getString("address_snapshot"),rs.getString("carrier_code"),rs.getString("tracking_no"),rs.getString("status"),rs.getObject("shipped_at", Long.class),rs.getObject("signed_at", Long.class)),tenantNo,id).stream().findFirst(); }
}
