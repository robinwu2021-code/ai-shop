package ai.neargo.commerce.persistence.trade;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallOrderItemRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MallOrderItemRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MallOrderItemRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mall_order_item` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`order_item_no`,`order_no`,`sku_no`,`sku_version_no`,`quantity`,`unit_price_minor`,`gross_minor`,`discount_minor`,`deal_minor`,`sku_snapshot`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.orderItemNo(),row.orderNo(),row.skuNo(),row.skuVersionNo(),row.quantity(),row.unitPriceMinor(),row.grossMinor(),row.discountMinor(),row.dealMinor(),row.skuSnapshot()); }
 public Optional<MallOrderItemRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mall_order_item` WHERE tenant_no=? AND id=?",(rs,n)->new MallOrderItemRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("order_item_no"),rs.getString("order_no"),rs.getString("sku_no"),rs.getString("sku_version_no"),rs.getObject("quantity", Integer.class),rs.getObject("unit_price_minor", Long.class),rs.getObject("gross_minor", Long.class),rs.getObject("discount_minor", Long.class),rs.getObject("deal_minor", Long.class),rs.getString("sku_snapshot")),tenantNo,id).stream().findFirst(); }
}
