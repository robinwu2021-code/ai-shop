package ai.neargo.shop.event.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class PlatformOutboxPublisherTest extends OutboxTestFixture {
    record Event(String aggregateId) implements DomainEvent {
        public String aggregateType() { return "CONTRACT"; }
        public String eventType() { return "CONTRACT_PUBLISHED"; }
    }

    private OutboxPublisher publisher(UnifiedOutboxFacade facade) {
        var type = assertDoesNotThrow(() -> Class.forName(
                "ai.neargo.shop.event.outbox.runtime.PlatformOutboxPublisher"));
        return assertDoesNotThrow(() -> (OutboxPublisher) type
                .getConstructor(Supplier.class, PlatformTransactionManager.class)
                .newInstance((Supplier<UnifiedOutboxFacade>) () -> facade, tx.getTransactionManager()));
    }

    private UnifiedOutboxFacade facade() {
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of());
        facade.setArchiveOnly(Set.of("PLATFORM:CONTRACT_PUBLISHED"));
        return facade;
    }

    @Test
    void rejectsMissingPlatformAndTransactionlessPublication() {
        var absent = publisher(null);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                absent.publish(new Event("E"), new PublishOptions("CONTRACT:E", 1))));
        var ready = publisher(facade());
        assertThrows(IllegalStateException.class, () -> ready.publish(new Event("E"),
                new PublishOptions("CONTRACT:E", 1)));
    }

    @Test
    void joinsSourceCommitAndRollsBackWithIt() {
        var publisher = publisher(facade());
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            publisher.publish(new Event("E"), new PublishOptions("CONTRACT:E", 1));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class));
        tx.executeWithoutResult(status -> publisher.publish(new Event("E"), new PublishOptions("CONTRACT:E", 1)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class));
    }
    @Test
    void rejectsUnroutedEventInsteadOfCommittingFailedDelivery() {
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of());
        var publisher = publisher(facade);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                publisher.publish(new Event("E"), new PublishOptions("CONTRACT:E", 1))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class));
    }

}
