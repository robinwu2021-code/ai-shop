package ai.neargo.commerce.persistence;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical JDBC transaction ownership, using Spring's transaction-manager SPI. */
final class JdbcTransactionOwnership {
    private JdbcTransactionOwnership() {}

    static boolean active(DataSource ds) {
        return new Probe(ds).active();
    }

    static void rejectForeignTransactions(DataSource ds) {
        // Another manager can leave our outer physical holder bound while starting its own
        // transaction. Those two resources cannot provide one local atomic commit boundary.
        for (Object resource : TransactionSynchronizationManager.getResourceMap().keySet()) {
            if (resource instanceof DataSource other && other != ds && active(other))
                throw new IllegalStateException("Commerce cannot mix different DataSource transactions");
        }
    }

    private static final class Probe extends DataSourceTransactionManager {
        Probe(DataSource ds) {
            super(ds);
        }

        boolean active() {
            // doGetTransaction only inspects the bound holder. isExistingTransaction checks
            // Spring's physical transaction flag, unlike synchronization binding or autoCommit.
            return isExistingTransaction(doGetTransaction());
        }
    }
}
