package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.PublishReceipt;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;

/** Reuses the owner's unified PLATFORM source for retail and industry adapters; does not create a runtime. */
public final class PlatformOutboxPublisher implements OutboxPublisher {
    private final Supplier<UnifiedOutboxFacade> facades;

    public PlatformOutboxPublisher(Supplier<UnifiedOutboxFacade> facades, PlatformTransactionManager manager) {
        this.facades = Objects.requireNonNull(facades);
        OutboxTransactions.register(manager);
    }

    @Override
    public PublishReceipt publish(DomainEvent event, PublishOptions options) {
        UnifiedOutboxFacade facade = facades.get();
        if (facade == null || !facade.enabled("PLATFORM")) {
            throw new IllegalStateException("A registered unified PLATFORM outbox source is required");
        }
        if (!facade.hasRoute("PLATFORM", event.eventType())) {
            throw new IllegalStateException("No registered PLATFORM route for event: " + event.eventType());
        }
        return facade.publisher("PLATFORM").publish(event, options);
    }
}
