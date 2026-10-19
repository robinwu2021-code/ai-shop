package ai.neargo.shop.pay.entitlement.event;

import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Writes source facts in pay and defers durable bridging until the pay transaction commits. */
@Component
public class PayEntitlementEventPublisher {
    private static final String TENANT = "MAIN";
    private static final String SYSTEM = "SYSTEM";
    private final PayEntOutboxMapper outbox;
    private final ObjectMapper json;
    private final ObjectProvider<PayEntitlementOutboxRelay> relayProvider;

    public PayEntitlementEventPublisher(PayEntOutboxMapper outbox, ObjectMapper json,
                                        ObjectProvider<PayEntitlementOutboxRelay> relayProvider) {
        this.outbox = outbox;
        this.json = json;
        this.relayProvider = relayProvider;
    }

    @Transactional(transactionManager = "payTxManager", propagation = Propagation.MANDATORY)
    public void publish(PayEntitlementEvent event) {
        validate(event);
        String payload = json.writeValueAsString(event);
        String hash = hash(event, payload);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        var row = new PayEntOutbox();
        row.setTenantNo(TENANT);
        row.setEventNo(event.eventNo());
        row.setEventType(event.eventType());
        row.setSchemaVersion(event.schemaVersion());
        row.setAggregateNo(event.aggregateId());
        row.setPayload(payload);
        row.setPayloadHash(hash);
        row.setOccurredAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(event.occurredAt()), ZoneOffset.UTC));
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setNextAttemptAt(now);
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(SYSTEM);
        row.setUpdatedBy(SYSTEM);
        outbox.insertIfAbsent(row);

        PayEntOutbox persisted = outbox.findByEventNo(TENANT, event.eventNo());
        if (persisted == null || !sameFacts(row, persisted)) {
            throw new IllegalStateException("Entitlement event number already exists with different immutable facts");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    PayEntitlementOutboxRelay relay = relayProvider.getIfAvailable();
                    if (relay != null) relay.submit(event.eventNo());
                }
            });
        }
    }

    private static void validate(PayEntitlementEvent event) { PayEntitlementEventCodec.validate(event); }

    private static boolean sameFacts(PayEntOutbox a, PayEntOutbox b) {
        return a.getEventType().equals(b.getEventType()) && a.getSchemaVersion().equals(b.getSchemaVersion())
                && a.getAggregateNo().equals(b.getAggregateNo())
                && a.getPayloadHash().equals(b.getPayloadHash()) && a.getOccurredAt().equals(b.getOccurredAt());
    }

    static String hash(PayEntitlementEvent event, String payload) {
        return PayEntitlementEventCodec.hash(event, payload);
    }
}
