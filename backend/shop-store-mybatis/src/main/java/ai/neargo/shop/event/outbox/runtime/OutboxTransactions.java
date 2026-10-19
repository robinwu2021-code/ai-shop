package ai.neargo.shop.event.outbox.runtime;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.sql.DataSource;

/** Tracks the innermost physical transaction; a synchronized JDBC read is not ownership. */
public final class OutboxTransactions {
    private static final class Owner {
        private final TransactionExecution transaction;
        private final DataSource dataSource;
        private boolean publishing = true;
        Owner(TransactionExecution transaction, DataSource dataSource) {
            this.transaction = transaction; this.dataSource = dataSource;
        }
        TransactionExecution transaction() { return transaction; }
        DataSource dataSource() { return dataSource; }
    }
    private static final ThreadLocal<ArrayDeque<Owner>> OWNERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<PlatformTransactionManager, Boolean> REGISTERED = Collections.synchronizedMap(new WeakHashMap<>());
    private OutboxTransactions() { }
    public static void register(PlatformTransactionManager manager) {
        synchronized (REGISTERED) {
            if (REGISTERED.containsKey(manager)) { return; }
            if (!(manager instanceof DataSourceTransactionManager jdbc)) {
                throw new IllegalArgumentException("Unified outbox requires a JDBC transaction manager");
            }
            DataSource source = jdbc.getDataSource();
            jdbc.addListener(new TransactionExecutionListener() {
                @Override public void afterBegin(TransactionExecution transaction, Throwable failure) {
                    if (failure == null && transaction.isNewTransaction()) { OWNERS.get().push(new Owner(transaction, source)); }
                }
                @Override public void beforeCommit(TransactionExecution transaction) {
                    OWNERS.get().stream().filter(owner -> owner.transaction() == transaction)
                            .forEach(owner -> owner.publishing = false);
                }
                @Override public void beforeRollback(TransactionExecution transaction) {
                    OWNERS.get().stream().filter(owner -> owner.transaction() == transaction)
                            .forEach(owner -> owner.publishing = false);
                }
                @Override public void afterCommit(TransactionExecution transaction, Throwable failure) { end(transaction); }
                @Override public void afterRollback(TransactionExecution transaction, Throwable failure) { end(transaction); }
                private void end(TransactionExecution transaction) {
                    var owners = OWNERS.get();
                    owners.removeIf(owner -> owner.transaction() == transaction);
                    if (owners.isEmpty()) { OWNERS.remove(); }
                }
            });
            REGISTERED.put(manager, true);
        }
    }
    public static Object currentIdentity() {
        var owners = OWNERS.get();
        if (owners.isEmpty()) { throw new IllegalStateException("No physical source transaction"); }
        return owners.peek().transaction();
    }
    public static boolean owns(DataSource source) {
        var owners = OWNERS.get();
        return !owners.isEmpty() && owners.peek().dataSource() == source && owners.peek().publishing;
    }
}
