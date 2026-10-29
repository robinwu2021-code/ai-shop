package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Explicit immutable-publication evidence route over the existing platform ledger; never a dispatcher. */
public final class PlatformPublicationPublisher {
    private PlatformPublicationPublisher() { }

    public static OutboxPublisher create(Supplier<UnifiedOutboxFacade> global,
            PlatformTransactionManager manager, Supplier<DataSource> sources, ObjectMapper json,
            Supplier<Stream<OutboxConsumer>> consumers, Set<String> eventTypes, boolean archiveOnly,
            boolean maintenance) {
        if (!archiveOnly) { return new PlatformOutboxPublisher(global, manager); }
        Set<String> allowed = eventTypes.stream().map(type -> "PLATFORM:" + type)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var archive = new UnifiedOutboxFacade(json);
        archive.setArchiveOnly(allowed);
        archive.setMaintenance(maintenance);
        archive.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, sources.get(), manager, json), List.of());
        var publisher = new PlatformOutboxPublisher(() -> archive, manager);
        return (event, options) -> {
            if (event == null || !allowed.contains("PLATFORM:" + event.eventType())) {
                throw new IllegalStateException("Only explicitly allowed immutable publications may be archived");
            }
            try (var declared = consumers.get()) {
                if (declared.anyMatch(consumer -> consumer.supports(event.eventType()))) {
                    throw new IllegalStateException("Publication archive conflicts with a declared business consumer");
                }
            }
            var facade = global.get();
            if (facade != null && facade.enabled("PLATFORM") && facade.hasRoute("PLATFORM", event.eventType())) {
                throw new IllegalStateException("Publication archive conflicts with a registered delivery route");
            }
            return publisher.publish(event, options);
        };
    }
}
