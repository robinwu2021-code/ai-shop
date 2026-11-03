package ai.neargo.commerce.persistence;

import ai.neargo.commerce.core.spi.CommerceUnitOfWork;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

import javax.sql.DataSource;

public final class JdbcCommerceUnitOfWork implements CommerceUnitOfWork {
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public JdbcCommerceUnitOfWork(DataSource ds, PlatformTransactionManager manager) {
        if (!(manager instanceof DataSourceTransactionManager jdbc) || jdbc.getDataSource() != ds)
            throw new IllegalArgumentException(
                    "Use the host JDBC transaction manager and the identical DataSource");
        dataSource = ds;
        transactions = new TransactionTemplate(manager);
    }

    public <T> T required(Supplier<T> work) {
        JdbcTransactionOwnership.rejectForeignTransactions(dataSource);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !JdbcTransactionOwnership.active(dataSource))
            throw new IllegalStateException("Commerce requires the current shared DataSource transaction");
        return transactions.execute(status -> work.get());
    }
}
