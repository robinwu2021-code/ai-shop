package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.OutboxIngress;
import java.util.Set;
import java.util.function.BiFunction;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.PublishReceipt;
import ai.neargo.shop.event.outbox.RetryPolicy;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Shared routing and publication facade; registration is completed before the lifecycle starts. */
public final class UnifiedOutboxFacade {
    private final ObjectMapper json;
    private boolean maintenance;
    private Set<String> archiveOnly = Set.of();
    public void setArchiveOnly(Set<String> types) { archiveOnly = Set.copyOf(types); }
    public OutboxPublisher publisher(String source) { return (event, options) -> publish(source, event, options); }
    public OutboxIngress ingress(String source) { return event -> accept(source, event); }
    private RetryPolicy retryPolicy = RetryPolicy.defaults();
    public void setMaintenance(boolean value) { maintenance = value; }
    public boolean isMaintenance() { return maintenance; }
    public void setRetryPolicy(RetryPolicy policy) { retryPolicy = policy; }
    private final Map<String, JdbcOutboxStore> stores = new ConcurrentHashMap<>();
    private final Map<String, Map<String, OutboxHandler>> handlers = new ConcurrentHashMap<>();
    private BiFunction<String, DomainEvent, PublishOptions> routes =
            (source, event) -> new PublishOptions(event.aggregateType() + ":" + event.aggregateId(), 1);
    private volatile Consumer<String> wakeup = source -> { };

    public UnifiedOutboxFacade(ObjectMapper json) { this.json = json; }

    public void register(JdbcOutboxStore store, List<OutboxHandler> sourceHandlers) {
        Map<String, OutboxHandler> map = new LinkedHashMap<>();
        for (OutboxHandler handler : sourceHandlers) {
            String id = handler.handlerId();
            if (id == null || id.isBlank() || id.length() > 128 || map.putIfAbsent(id, handler) != null) {
                throw new IllegalStateException("Duplicate or invalid outbox handler ID");
            }
        }
        if (stores.putIfAbsent(store.source(), store) != null) {
            throw new IllegalStateException("Duplicate outbox source");
        }
        store.setRetryPolicy(retryPolicy);
        handlers.put(store.source(), Map.copyOf(map));
    }

    public Collection<JdbcOutboxStore> stores() { return List.copyOf(stores.values()); }
    public JdbcOutboxStore store(String source) {
        JdbcOutboxStore store = stores.get(source);
        if (store == null) { throw new IllegalStateException("Outbox source not registered: " + source); }
        return store;
    }
    public boolean enabled(String source) { return stores.containsKey(source); }
    public OutboxHandler handler(String source, String id) {
        return handlers.getOrDefault(source, Map.of()).get(id);
    }
    public void setRoutes(BiFunction<String, DomainEvent, PublishOptions> routes) {
        this.routes = routes;
    }
    public PublishReceipt publish(String source, DomainEvent event) {
        return publisher(source).publish(event, routes.apply(source, event));
    }
    public void setWakeup(Consumer<String> wakeup) { this.wakeup = wakeup; }

    public PublishReceipt publish(String source, DomainEvent event, PublishOptions options) {
        if (maintenance) { throw new IllegalStateException("Outbox source is under maintenance"); }
        String eventNo = BizKey.next(BizKey.EVENT);
        OutboxEnvelope envelope = new OutboxEnvelope(eventNo, source, eventNo, event.aggregateType(),
                event.aggregateId(), event.eventType(), options.schemaVersion(), options.streamKey(), 0,
                json.writeValueAsString(event), Instant.now());
        Map<String, Boolean> targets = targets(source, event.eventType());
        boolean archive = archiveOnly.contains(source + ":" + event.eventType());
        if (archive && !targets.isEmpty()) { throw new IllegalStateException("Archive route conflicts with consumers"); }
        PublishReceipt receipt = store(source).append(envelope, targets, archive);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { signalSafely(source); }
        });
        return receipt;
    }

    public PublishReceipt accept(String source, OutboxEnvelope envelope) {
        if (maintenance) { throw new IllegalStateException("Outbox source is under maintenance"); }
        PublishReceipt result = store(source).accept(envelope, targets(source, envelope.eventType()));
        signalSafely(source);
        return result;
    }

    private Map<String, Boolean> targets(String source, String type) {
        Map<String, Boolean> targets = new LinkedHashMap<>();
        handlers.getOrDefault(source, Map.of()).values().stream().filter(h -> h.supports(type))
                .forEach(h -> targets.put(h.handlerId(), h.externalWorkflow()));
        return targets;
    }
    public void signal(String source) { signalSafely(source); }

    /** Legacy inventory sinks lack a transactional publisher but can use stable ingress identity. */
    public PublishReceipt acceptInventory(String eventNo, String ownerId, String type,
            String payload, Instant occurredAt) {
        String document = json.readTree(payload).path("docNo").asString(eventNo);
        String wrapped = json.writeValueAsString(Map.of("ownerId", ownerId, "type", type, "payload", payload));
        return accept("PLATFORM", new OutboxEnvelope(BizKey.next(BizKey.EVENT), "INVENTORY", eventNo,
                "INVENTORY", ownerId, "INV_" + type, 1, "INVENTORY:DOCUMENT:" + document, 0, wrapped, occurredAt));
    }

    private void signalSafely(String source) {
        try { wakeup.accept(source); } catch (RuntimeException ignored) {
            // The durable recovery path remains authoritative after commit.
        }
    }
}
