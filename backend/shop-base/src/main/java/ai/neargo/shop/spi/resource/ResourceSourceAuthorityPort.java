package ai.neargo.shop.spi.resource;

import java.time.Instant;
import java.util.Set;

/**
 * Source-owned durable fence authority shared by industries, separate from payment or entitlement decisions.
 * Providers must authenticate scope/operator, compare their persisted complete intent, and serialize contrary
 * source actions on a durable fence. Repeated freeze/query uses the original Binding; lost responses are not denial.
 * A fence survives the permission deadline until the original command's durable outcome has been reconciled.
 */
public interface ResourceSourceAuthorityPort {
    /** Unique server namespace; duplicate providers must prevent startup, never use first-match. */
    String sourceType();

    /** Freeze a committed source revision idempotently; caller must hold no resource transaction or locks. */
    Decision freeze(Request request);

    /** Query the original durable fence after uncertain results; UNKNOWN/PENDING never authorizes mutation. */
    Decision query(Request request);

    /** Immutable command identity; hashes cover target versions and the complete qualification policy. */
    record Binding(String tenantNo, String entityNo, String storeNo, String commandNo, String sourceType,
            String sourceNo, long sourceRevision, String action, String commandHash, String planHash) {
        public Binding {
            ResourceSourceAuthorityPort.text(tenantNo, 32);
            ResourceSourceAuthorityPort.text(entityNo, 32);
            ResourceSourceAuthorityPort.text(storeNo, 32);
            ResourceSourceAuthorityPort.text(commandNo, 32);
            ResourceSourceAuthorityPort.text(sourceNo, 64);
            if (sourceType == null || !sourceType.matches("[A-Z][A-Z0-9_]{0,31}") || sourceRevision < 0
                    || !Set.of("RESERVE", "PROTECT", "CONFIRM", "RENEW", "RELEASE", "EXPIRE", "RECONCILE", "REPLACE")
                            .contains(action == null ? "" : action)) {
                throw new IllegalArgumentException("valid source/action/revision required");
            }
            ResourceSourceAuthorityPort.hash(commandHash);
            ResourceSourceAuthorityPort.hash(planHash);
        }
    }

    /** Current trusted recovery actor is checked by the source owner; it need not be the original initiator. */
    record Request(Binding binding, String operatorNo, String realm, String subjectKind) {
        public Request {
            if (binding == null) {
                throw new IllegalArgumentException("source binding required");
            }
            ResourceSourceAuthorityPort.text(operatorNo, 64);
            if (realm == null || !realm.matches("[A-Z][A-Z0-9_]{0,31}")
                    || subjectKind == null || !subjectKind.matches("[A-Z][A-Z0-9_]{0,31}")) {
                throw new IllegalArgumentException("explicit trusted operator realm and subject kind required");
            }
        }
    }

    /** Source truth only; none of these states describes the current resource hold lifecycle. */
    enum State {
        UNKNOWN, PENDING, DENIED, AUTHORIZED
    }

    /** A permission window limits execution, not source-fence retention; never expose this as client authorization. */
    record Decision(Binding binding, State state, String decisionNo, String fenceNo, Long fenceRevision,
            Instant issuedAt, Instant validUntil, String reasonCode) {
        public Decision {
            if (binding == null || state == null) {
                throw new IllegalArgumentException("bound source decision required");
            }
            if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("safe source reason code required");
            }
            if (state == State.AUTHORIZED) {
                ResourceSourceAuthorityPort.text(decisionNo, 64);
                ResourceSourceAuthorityPort.text(fenceNo, 64);
                ResourceSourceAuthorityPort.time(issuedAt);
                ResourceSourceAuthorityPort.time(validUntil);
                if (fenceRevision == null || fenceRevision < 0 || !validUntil.isAfter(issuedAt)
                        || reasonCode != null) {
                    throw new IllegalArgumentException("complete effective fence evidence required");
                }
            } else {
                if (fenceNo != null || fenceRevision != null || issuedAt != null || validUntil != null) {
                    throw new IllegalArgumentException("non-authorized decision cannot carry permission");
                }
                if (state == State.DENIED) {
                    ResourceSourceAuthorityPort.text(decisionNo, 64);
                    if (reasonCode == null) {
                        throw new IllegalArgumentException("denial must name its durable reason");
                    }
                } else if (decisionNo != null || state == State.UNKNOWN && reasonCode != null) {
                    throw new IllegalArgumentException("unknown/pending cannot carry a durable permission decision");
                }
            }
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw new IllegalArgumentException("bounded canonical reference required");
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("lowercase SHA256 required");
        }
    }

    private static void time(Instant value) {
        if (value == null || value.getNano() % 1_000_000 != 0
                || value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))) {
            throw new IllegalArgumentException("storage-safe millisecond source time required");
        }
    }
}
