package ai.neargo.commerce.persistence.merchandise;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.commerce.persistence.JdbcWriteGuard;
/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallProductRepository {
 private final JdbcTemplate jdbc; private final DataSource dataSource;
 public MallProductRepository(DataSource ds) { dataSource=ds; jdbc=new JdbcTemplate(ds); }
 public void insert(MallProductRecord row) { JdbcWriteGuard.require(dataSource, row.tenantNo()); jdbc.update("INSERT INTO `mall_product` (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`product_no`,`category_code`,`product_type`,`name`,`description`,`media`,`allowed_business_types`,`sale_status`,`sort`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.tenantNo(),row.createdAt(),row.updatedAt(),row.createdBy(),row.updatedBy(),row.version(),row.deleted(),row.productNo(),row.categoryCode(),row.productType(),row.name(),row.description(),row.media(),row.allowedBusinessTypes(),row.saleStatus(),row.sort()); }
 public Optional<MallProductRecord> findById(String tenantNo,long id) { JdbcWriteGuard.tenant(tenantNo); return jdbc.query("SELECT * FROM `mall_product` WHERE tenant_no=? AND id=?",(rs,n)->new MallProductRecord(rs.getObject("id", Long.class),rs.getString("tenant_no"),rs.getObject("created_at", java.time.LocalDateTime.class),rs.getObject("updated_at", java.time.LocalDateTime.class),rs.getString("created_by"),rs.getString("updated_by"),rs.getObject("version", Long.class),rs.getObject("deleted", Integer.class),rs.getString("product_no"),rs.getString("category_code"),rs.getString("product_type"),rs.getString("name"),rs.getString("description"),rs.getString("media"),rs.getString("allowed_business_types"),rs.getString("sale_status"),rs.getObject("sort", Integer.class)),tenantNo,id).stream().findFirst(); }
}
