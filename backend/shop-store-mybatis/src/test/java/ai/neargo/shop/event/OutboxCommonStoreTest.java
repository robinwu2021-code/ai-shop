package ai.neargo.shop.event;

import ai.neargo.shop.common.config.CommonTableMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10.2（ADR-067 决策 10）：发件箱只在基础能力库。基础能力库与业务库分开绑定时没有共同事务，
 * 发件箱行在业务提交后以基础能力库的 REQUIRES_NEW 事务写入；回滚不写。没分开时照旧同事务写。
 */
@DisplayName("发件箱写在基础能力库")
class OutboxCommonStoreTest {

    private record Evt(String id) implements DomainEvent {
        @Override public String aggregateType() { return "ORDER"; }
        @Override public String aggregateId() { return id; }
        @Override public String eventType() { return "ORDER_PAID"; }
    }

    /** 记下开了几次 REQUIRES_NEW 事务。 */
    static final class RecordingTx extends AbstractPlatformTransactionManager {
        final List<Integer> propagations = new ArrayList<>();
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition d) { propagations.add(d.getPropagationBehavior()); }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private final SysOutboxMapper mapper = mock(SysOutboxMapper.class);
    private final RecordingTx commonTx = new RecordingTx();

    @SuppressWarnings("unchecked")
    private OutboxEventBus bus(boolean separate) {
        when(mapper.insert(any(SysOutbox.class))).thenAnswer(inv -> {
            ((SysOutbox) inv.getArgument(0)).setId(1L);
            return 1;
        });
        ObjectProvider<OutboxPump> pumps = mock(ObjectProvider.class);
        OutboxEventBus bus = new OutboxEventBus(mapper, new ObjectMapper(), pumps);
        DataSource main = mock(DataSource.class);
        bus.setStores(main, separate ? mock(DataSource.class) : main, commonTx);
        return bus;
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("发件箱 Mapper 挂在基础能力库句柄上")
    void mapperIsOnCommonHandle() {
        assertThat(CommonTableMapper.class).isAssignableFrom(SysOutboxMapper.class);
    }

    @Test
    @DisplayName("分开绑定：业务事务里不写，提交后在基础能力库的独立事务里写")
    void separateStoresWriteAfterCommit() {
        var bus = bus(true);
        TransactionSynchronizationManager.initSynchronization();
        bus.publish(new Evt("O1"));
        verify(mapper, never()).insert(any(SysOutbox.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(mapper, times(1)).insert(any(SysOutbox.class));
        assertThat(commonTx.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("分开绑定：业务回滚就不写")
    void separateStoresRollbackWritesNothing() {
        var bus = bus(true);
        TransactionSynchronizationManager.initSynchronization();
        bus.publish(new Evt("O2"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(mapper, never()).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("没分开绑定：照旧在业务事务里写")
    void sameStoreWritesInBusinessTransaction() {
        var bus = bus(false);
        TransactionSynchronizationManager.initSynchronization();
        bus.publish(new Evt("O3"));
        verify(mapper, times(1)).insert(any(SysOutbox.class));
        assertThat(commonTx.propagations).isEmpty();
    }
}
