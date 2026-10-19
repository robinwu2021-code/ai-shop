package ai.neargo.shop.pay.entitlement.event;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.EntitlementEvents;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import tools.jackson.databind.ObjectMapper;

/** Bridges durable pay source events into the shared ordered/retrying system outbox. */
public class PayEntitlementOutboxRelay {
    private static final int MAX_BRIDGE_ATTEMPTS = 10;
    private static final Logger log = LoggerFactory.getLogger(PayEntitlementOutboxRelay.class);
    private static final String TENANT = "MAIN";
    private static final int BATCH_SIZE = 100;
    private final PayEntOutboxMapper outbox;
    private final PayEntitlementEventBridgePort bridge;
    private final ObjectMapper json;
    private final ScheduledExecutorService retryTimer = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "pay-entitlement-outbox-retry"));
    private final ExecutorService relayWorker = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "pay-entitlement-outbox-bridge"));

    public PayEntitlementOutboxRelay(PayEntOutboxMapper outbox, PayEntitlementEventBridgePort bridge,
            ObjectMapper json) {
        this.outbox = outbox; this.bridge = bridge; this.json = json;
    }
    /** Existing native fixtures use a real core bus directly; production always binds the owned bridge. */
    public PayEntitlementOutboxRelay(PayEntOutboxMapper outbox, OutboxEventBus bus, ObjectMapper json) {
        this(outbox, bus::publish, json);
    }

    public void relay(String eventNo) {
        var row = outbox.findByEventNo(TENANT, eventNo);
        if (row == null || !"PENDING".equals(row.getStatus())) return;
        try {
            PayEntitlementEvent event = decode(row);
            bridge.publish(eventNo, event);
            outbox.markForwarded(row.getId(), TENANT, LocalDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int attempts = row.getAttempts() == null ? 0 : row.getAttempts();
            if (attempts + 1 >= MAX_BRIDGE_ATTEMPTS) {
                outbox.markDead(row.getId(), TENANT, now);
                log.error("pay entitlement outbox bridge exhausted retries eventNo={} type={}",
                        row.getEventNo(), row.getEventType(), e);
                return;
            }
            long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
            outbox.defer(row.getId(), TENANT, now, now.plusSeconds(delaySeconds));
            retryTimer.schedule(() -> relay(row.getEventNo()), delaySeconds, TimeUnit.SECONDS);
            log.warn("pay entitlement outbox bridge failed eventNo={} type={} : {}",
                    row.getEventNo(), row.getEventType(), e.toString());
        }
    }

    /** Queue work off the pay after-commit thread so the platform write gets a fresh session. */
    public void submit(String eventNo) { relayWorker.execute(() -> relay(eventNo)); }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        relayPendingSafely();
        retryTimer.scheduleWithFixedDelay(this::relayPendingSafely, 5, 5, TimeUnit.SECONDS);
    }

    public int relayPending() {
        List<PayEntOutbox> rows = outbox.selectPending(TENANT, LocalDateTime.now(ZoneOffset.UTC), BATCH_SIZE);
        rows.forEach(row -> submit(row.getEventNo()));
        return rows.size();
    }

    private void relayPendingSafely() {
        try { relayPending(); }
        catch (RuntimeException e) { log.warn("pay entitlement outbox recovery scan failed: {}", e.toString()); }
    }

    @PreDestroy
    public void shutdown() {
        retryTimer.shutdownNow();
        relayWorker.shutdownNow();
    }

    private PayEntitlementEvent decode(PayEntOutbox row) {
        PayEntitlementEvent event = switch (row.getEventType()) {
            case "ENTITLEMENT_BATCH_PREPARED" ->
                    json.readValue(row.getPayload(), EntitlementEvents.EntitlementBatchPrepared.class);
            case "ENTITLEMENT_GRANTED" -> json.readValue(row.getPayload(), EntitlementEvents.EntitlementGranted.class);
            case "ENTITLEMENT_USE_REVERSED" ->
                    json.readValue(row.getPayload(), EntitlementEvents.EntitlementUseReversed.class);
            case "ENTITLEMENT_USED" -> json.readValue(row.getPayload(), EntitlementEvents.EntitlementUsed.class);
            case "STORED_VALUE_CREDITED" -> json.readValue(row.getPayload(),
                    EntitlementEvents.StoredValueCredited.class);
            default -> throw new IllegalArgumentException("Unknown pay entitlement event type");
        };
        PayEntitlementEventCodec.validate(event);
        String canonicalPayload = json.writeValueAsString(event);
        if (!event.eventNo().equals(row.getEventNo()) || event.schemaVersion() != row.getSchemaVersion()
                || !event.eventType().equals(row.getEventType()) || !event.aggregateId().equals(row.getAggregateNo())
                || !json.readTree(canonicalPayload).equals(json.readTree(row.getPayload()))
                || !PayEntitlementEventPublisher.hash(event, canonicalPayload).equals(row.getPayloadHash())) {
            throw new IllegalStateException("Pay entitlement event payload does not match its immutable header");
        }
        return event;
    }
}
