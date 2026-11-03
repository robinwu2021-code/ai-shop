package ai.neargo.commerce.persistence;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
public final class JdbcWriteGuard {
 private JdbcWriteGuard() {}
 public static void tenant(String tenant) {if(tenant==null||tenant.isBlank())throw new IllegalArgumentException("tenant is required");}
 public static void require(DataSource ds,String tenant){tenant(tenant);if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(ds))throw new IllegalStateException("Commerce writes require the shared DataSource transaction");}
}
