package ai.neargo.shop.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 只发通知的事件：不落发件箱，提交后在别的线程交给通知消费者，丢了不重投。 */
@DisplayName("提交后再发的通知")
class AfterCommitNotificationsTest {

    private record Arrived(String id) implements DomainEvent {
        @Override public String aggregateType() { return "ORDER"; }
        @Override public String aggregateId() { return id; }
        @Override public String eventType() { return "ORDER_ARRIVED"; }
    }

    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();

    private final OutboxConsumer notify = new OutboxConsumer() {
        @Override public boolean afterCommitOnly() { return true; }
        @Override public boolean supports(String type) { return "ORDER_ARRIVED".equals(type); }
        @Override public void consume(SysOutbox e) {
            seen.add(e.getEventType() + ":" + e.getAggregateId() + ":" + e.getPayload()
                    + ":" + (e.getEventNo() != null));
            threads.add(Thread.currentThread().getName());
        }
    };
    private final OutboxConsumer other = new OutboxConsumer() {
        @Override public boolean supports(String type) { return false; }
        @Override public void consume(SysOutbox e) { seen.add("other"); }
    };
    private final OutboxConsumer broken = new OutboxConsumer() {
        @Override public boolean afterCommitOnly() { return true; }
        @Override public boolean supports(String type) { return true; }
        @Override public void consume(SysOutbox e) { throw new IllegalStateException("push down"); }
    };

    /** 必达消费者从发件箱收，提交后通知不碰它。 */
    private final OutboxConsumer durable = new OutboxConsumer() {
        @Override public boolean supports(String type) { return true; }
        @Override public void consume(SysOutbox e) { seen.add("durable"); }
    };

    private AfterCommitNotifications notifications(OutboxConsumer... consumers) {
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        return new AfterCommitNotifications(provider, new ObjectMapper(), true);
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("事务里发布：提交前什么都不发，提交后在另一个线程交给支持它的消费者")
    void deliversOnlyAfterCommitOnAnotherThread() {
        var n = notifications(broken, notify, other, durable);
        TransactionSynchronizationManager.initSynchronization();
        n.publish(new Arrived("O1"));
        assertThat(seen).isEmpty();

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);

        assertThat(seen).containsExactly("ORDER_ARRIVED:O1:{\"id\":\"O1\"}:true");
        assertThat(threads).doesNotContain(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("配了中间件：提交后发给中间件而不是本地调消费者；中间件暂时不可用就重试，用尽只记日志")
    void brokerReceivesAfterCommitWithRetry() {
        var n = notifications(notify);
        var broker = mock(ai.neargo.shop.event.broker.MessageBroker.class);
        org.mockito.Mockito.doThrow(new ai.neargo.shop.event.broker.BrokerException("down"))
                .doNothing().when(broker).publish(org.mockito.ArgumentMatchers.any());
        var props = new ai.neargo.shop.event.broker.BrokerProperties();
        props.setSendBackoffMillis(0);
        n.setBroker(broker);
        n.setBrokerProperties(props);
        TransactionSynchronizationManager.initSynchronization();
        n.publish(new Arrived("O4"));
        org.mockito.Mockito.verifyNoInteractions(broker);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        org.mockito.Mockito.verify(broker, org.mockito.Mockito.times(2)).publish(org.mockito.ArgumentMatchers.argThat(
                m -> m.eventType().equals("ORDER_ARRIVED") && m.aggregateId().equals("O4") && m.eventNo() != null));
        assertThat(seen).as("交给中间件，不再本地调").isEmpty();
    }

    @Test
    @DisplayName("回滚了就不发")
    void rollbackSendsNothing() {
        var n = notifications(notify);
        TransactionSynchronizationManager.initSynchronization();
        n.publish(new Arrived("O2"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("没有事务时直接发")
    void noTransactionSendsNow() {
        notifications(notify).publish(new Arrived("O3"));
        assertThat(seen).containsExactly("ORDER_ARRIVED:O3:{\"id\":\"O3\"}:true");
    }
}
