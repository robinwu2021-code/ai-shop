package ai.neargo.shop.pay.entitlement.event;

import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.EntitlementEvents;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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

    private static void validate(PayEntitlementEvent event) {
        if (event == null || !id(event.eventNo()) || !id(event.aggregateId()) || event.schemaVersion() != 1
                || event.occurredAt() <= 0) throw new IllegalArgumentException("Entitlement event identity is incomplete");
        if (event instanceof EntitlementEvents.EntitlementGranted granted) {
            if (!id(granted.grantNo()) || !id(granted.userNo()) || !id(granted.entityNo())
                    || !id(granted.templateVersionNo()) || !oneOf(granted.sourceType(), "ACTIVITY", "CENTER",
                    "TARGETED", "PAID_ORDER", "RECHARGE" ) || !id(granted.sourceNo()))
                throw new IllegalArgumentException("Entitlement grant event fields are incomplete");
        } else if (event instanceof EntitlementEvents.EntitlementUsed used) {
            boolean storedValue = "STORED_VALUE".equals(used.assetKind());
            if (!id(used.usageNo()) || !id(used.userNo()) || !id(used.entityNo()) || !id(used.storeNo())
                    || !oneOf(used.assetKind(), "ORDER_COUPON", "STORE_EXCHANGE", "PAID_CARD", "STORED_VALUE")
                    || !id(used.assetNo()) || (storedValue && (used.amountMinor() == null || used.amountMinor() <= 0
                    || used.units() != null)) || (!storedValue && (used.units() == null || used.units() <= 0
                    || used.amountMinor() != null)))
                throw new IllegalArgumentException("Entitlement usage event fields are incomplete");
        } else if (event instanceof EntitlementEvents.StoredValueCredited credited) {
            if (!id(credited.rechargeNo()) || !id(credited.accountNo()) || !id(credited.userNo())
                    || !id(credited.entityNo()) || !id(credited.storeNo()) || !id(credited.currency())
                    || credited.paidAmountMinor() < 0 || credited.creditedAmountMinor() < 0)
                throw new IllegalArgumentException("Stored value credit event fields are incomplete");
        } else {
            throw new IllegalArgumentException("Unsupported pay entitlement event type");
        }
    }

    private static boolean sameFacts(PayEntOutbox a, PayEntOutbox b) {
        return a.getEventType().equals(b.getEventType()) && a.getSchemaVersion().equals(b.getSchemaVersion())
                && a.getAggregateNo().equals(b.getAggregateNo())
                && a.getPayloadHash().equals(b.getPayloadHash()) && a.getOccurredAt().equals(b.getOccurredAt());
    }

    static String hash(PayEntitlementEvent event, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {event.eventType(), Integer.toString(event.schemaVersion()),
                    event.aggregateType(), event.aggregateId(), payload}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static boolean id(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
    }

    private static boolean oneOf(String value, String... accepted) {
        if (value == null) return false;
        for (String item : accepted) if (item.equals(value)) return true;
        return false;
    }
}
