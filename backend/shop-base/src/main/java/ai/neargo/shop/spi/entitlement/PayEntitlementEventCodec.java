package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementEventInternal.Envelope;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;

/** One immutable header/hash and event-field whitelist for both local and remote handoff. */
public final class PayEntitlementEventCodec {
    private PayEntitlementEventCodec() { }
    public static Envelope encode(PayEntitlementEvent event, ObjectMapper json) {
        validate(event);
        String payload = json.writeValueAsString(event);
        return new Envelope("MAIN", event.eventNo(), event.eventType(), event.schemaVersion(), event.aggregateType(),
                event.aggregateId(), payload, hash(event, payload), event.occurredAt());
    }
    public static PayEntitlementEvent decode(Envelope wire, ObjectMapper json) {
        if (!"MAIN".equals(wire.tenantNo())) throw new IllegalArgumentException("Event tenant is not enabled");
        PayEntitlementEvent event = read(wire.eventType(),wire.payload(),json);
        validate(event);
        String canonical = json.writeValueAsString(event);
        if (!wire.eventNo().equals(event.eventNo()) || !wire.eventType().equals(event.eventType())
                || wire.schemaVersion() != event.schemaVersion() || !wire.aggregateType().equals(event.aggregateType())
                || !wire.aggregateNo().equals(event.aggregateId()) || wire.occurredAt() != event.occurredAt()
                || !json.readTree(canonical).equals(json.readTree(wire.payload()))
                || !wire.payloadHash().equals(hash(event, canonical))) {
            throw new IllegalArgumentException("Entitlement event does not match its immutable header");
        }
        return event;
    }
    /** Central allowlist; callers must also validate immutable headers and hashes. */
    public static PayEntitlementEvent read(String type,String payload,ObjectMapper json) {
        return switch (type) {
            case "LEGACY_COUPON_ISSUE_COMPLETED" -> json.readValue(payload,EntitlementEvents.LegacyCouponIssueCompleted.class);
            case "ENTITLEMENT_BATCH_PREPARED" ->
                    json.readValue(payload, EntitlementEvents.EntitlementBatchPrepared.class);
            case "ENTITLEMENT_EXCEPTION_OPENED" -> json.readValue(payload,EntitlementEvents.ExceptionOpened.class);
            case "ENTITLEMENT_GRANTED" -> json.readValue(payload, EntitlementEvents.EntitlementGranted.class);
            case "ENTITLEMENT_USED" -> json.readValue(payload, EntitlementEvents.EntitlementUsed.class);
            case "ENTITLEMENT_USE_REVERSED" ->
                    json.readValue(payload, EntitlementEvents.EntitlementUseReversed.class);
            case "STORED_VALUE_RECHARGE_RECEIVED" ->
                    json.readValue(payload, EntitlementEvents.StoredValueRechargeReceived.class);
            case "STORED_VALUE_RECHARGE_CANCELLED" ->
                    json.readValue(payload, EntitlementEvents.StoredValueRechargeCancelled.class);
            case "LEGACY_COUPON_USAGE_CHANGED" -> json.readValue(payload, EntitlementEvents.LegacyCouponUsageChanged.class);
            case "STORED_VALUE_CREDITED" -> json.readValue(payload, EntitlementEvents.StoredValueCredited.class);
            default -> throw new IllegalArgumentException("Unsupported entitlement event type");
        };
    }
    public static void validate(PayEntitlementEvent event) {
        if (event == null || !id(event.eventNo()) || !id(event.aggregateId()) || event.schemaVersion() != 1
                || event.occurredAt() <= 0) {
            throw new IllegalArgumentException("Entitlement event identity is incomplete");
        }
        if(event instanceof EntitlementEvents.LegacyCouponIssueCompleted completed) {
            if(!"MAIN".equals(completed.tenantNo()) || !id(completed.issueNo()) || !id(completed.entityNo()) || !id(completed.actorNo()))
                throw new IllegalArgumentException("Legacy issue completion identity is incomplete");
        } else if (event instanceof EntitlementEvents.EntitlementBatchPrepared prepared) {
            if (!"MAIN".equals(prepared.tenantNo()) || !id(prepared.batchNo()) || !id(prepared.entityNo())
                    || !id(prepared.actorNo())) {
                throw new IllegalArgumentException("Batch prepared identity is incomplete");
            }
        } else if(event instanceof EntitlementEvents.ExceptionOpened opened) {
            if(!id(opened.exceptionNo()) || !id(opened.entityNo()) || !id(opened.bizNo())
                    || !"GRANT_DELIVERY".equals(opened.kind())) {
                throw new IllegalArgumentException("Entitlement exception event identity is incomplete");
            }
        } else if (event instanceof EntitlementEvents.EntitlementGranted granted) {
            if (!id(granted.grantNo()) || !id(granted.userNo()) || !id(granted.entityNo())
                    || !id(granted.templateVersionNo()) || !oneOf(granted.sourceType(), "ACTIVITY", "CENTER",
                    "TARGETED", "PAID_ORDER", "PAID_CARD_ORDER", "RECHARGE" ) || !id(granted.sourceNo()))
                throw new IllegalArgumentException("Entitlement grant event fields are incomplete");
        } else if (event instanceof EntitlementEvents.LegacyCouponUsageChanged legacy) {
            if(!id(legacy.usageNo()) || !id(legacy.userNo()) || !id(legacy.entityNo())
                    || !id(legacy.instanceNo()) || !id(legacy.orderNo()) || !oneOf(legacy.status(),"COMMITTED","REVERSED"))
                throw new IllegalArgumentException("Legacy usage event fields are incomplete");
        } else if (event instanceof EntitlementEvents.EntitlementUsed used) {
            boolean storedValue = "STORED_VALUE".equals(used.assetKind());
            if (!id(used.usageNo()) || !id(used.userNo()) || !id(used.entityNo()) || !id(used.storeNo())
                    || !oneOf(used.assetKind(), "ORDER_COUPON", "STORE_EXCHANGE", "PAID_CARD", "STORED_VALUE")
                    || !id(used.assetNo()) || (storedValue && (used.amountMinor() == null || used.amountMinor() <= 0
                    || used.units() != null)) || (!storedValue && (used.units() == null || used.units() <= 0
                    || used.amountMinor() != null)))
                throw new IllegalArgumentException("Entitlement usage event fields are incomplete");
        } else if (event instanceof EntitlementEvents.EntitlementUseReversed returned) {
            if (!id(returned.reverseNo()) || !id(returned.usageNo()) || !id(returned.userNo())
                    || !id(returned.entityNo()) || !id(returned.storeNo()) || !id(returned.instanceNo())
                    || !id(returned.currency()) || returned.restoredUnits() < 0 || returned.discountMinor() <= 0) {
                throw new IllegalArgumentException("Entitlement reversal event fields are incomplete");
            }
        } else if (event instanceof EntitlementEvents.StoredValueRechargeReceived received) {
            if (!"MAIN".equals(received.tenantNo()) || !id(received.rechargeNo()) || !id(received.entityNo()))
                throw new IllegalArgumentException("Stored value receipt event identity is incomplete");
        } else if (event instanceof EntitlementEvents.StoredValueRechargeCancelled cancelled) {
            if (!"MAIN".equals(cancelled.tenantNo()) || !id(cancelled.rechargeNo()) || !id(cancelled.entityNo()))
                throw new IllegalArgumentException("Stored value cancellation event identity is incomplete");
        } else if (event instanceof EntitlementEvents.StoredValueCredited credited) {
            if (!id(credited.rechargeNo()) || !id(credited.accountNo()) || !id(credited.customerNo()) || credited.userNo()!=null && !id(credited.userNo())
                    || !id(credited.entityNo()) || !id(credited.storeNo()) || !id(credited.currency())
                    || credited.paidAmountMinor() < 0 || credited.creditedAmountMinor() < 0)
                throw new IllegalArgumentException("Stored value credit event fields are incomplete");
        } else {
            throw new IllegalArgumentException("Unsupported pay entitlement event type");
        }
    }

    public static String hash(PayEntitlementEvent event, String payload) {
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
