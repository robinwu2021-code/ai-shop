package ai.neargo.shop.trade.entitlement;

import java.time.Instant;
import java.util.Objects;

/** A server-created, durable source intent. PENDING is deliberately not consumer authorization. */
public record AuthorizationIntent(String intentNo, String tenantNo, String userNo, String orderNo,
                                  long orderRevision, String quoteRef, String selectionDigest,
                                  String orderSnapshotHash, Instant expiresAt, State state,
                                  String requestKey, String authorizationNo, Instant authorizedAt) {
    public enum State { PENDING, AUTHORIZED, CANCELLED, EXPIRED }

    public AuthorizationIntent {
        requireText(intentNo); requireText(tenantNo); requireText(userNo); requireText(orderNo);
        requireText(quoteRef); requireHash(selectionDigest); requireHash(orderSnapshotHash);
        Objects.requireNonNull(expiresAt); Objects.requireNonNull(state);
        if (orderRevision < 0) throw new IllegalArgumentException("negative order revision");
        if (state == State.PENDING) {
            if (requestKey != null || authorizationNo != null || authorizedAt != null) {
                throw new IllegalArgumentException("pending intent cannot contain consumer authorization");
            }
        } else if (state == State.AUTHORIZED) {
            requireText(requestKey); requireText(authorizationNo); Objects.requireNonNull(authorizedAt);
            if (!authorizedAt.isBefore(expiresAt)) throw new IllegalArgumentException("authorization expired");
        }
    }

    public static AuthorizationIntent pending(String intentNo, String tenantNo, String userNo, String orderNo,
                                               long orderRevision, String quoteRef, String selectionDigest,
                                               String orderSnapshotHash, Instant expiresAt) {
        return new AuthorizationIntent(intentNo, tenantNo, userNo, orderNo, orderRevision, quoteRef,
                selectionDigest, orderSnapshotHash, expiresAt, State.PENDING, null, null, null);
    }

    public AuthorizationIntent confirm(String consumerNo, String idempotencyKey, Instant now) {
        Objects.requireNonNull(now);
        if (!userNo.equals(consumerNo)) throw new IllegalArgumentException("consumer mismatch");
        if (state == State.AUTHORIZED) {
            if (requestKey.equals(idempotencyKey)) return this;
            throw new IllegalStateException("intent already confirmed with another request key");
        }
        if (state != State.PENDING || !now.isBefore(expiresAt)) {
            throw new IllegalStateException("intent unavailable");
        }
        requireText(idempotencyKey);
        String authNo = "EA" + java.util.UUID.randomUUID().toString().replace("-", "");
        return new AuthorizationIntent(intentNo, tenantNo, userNo, orderNo, orderRevision, quoteRef,
                selectionDigest, orderSnapshotHash, expiresAt, State.AUTHORIZED, idempotencyKey, authNo, now);
    }

    public AuthorizationIntent revalidate(long currentRevision, String currentSelectionDigest,
                                          String currentSnapshotHash) {
        if (state != State.PENDING || currentRevision != orderRevision
                || !selectionDigest.equals(currentSelectionDigest) || !orderSnapshotHash.equals(currentSnapshotHash)) {
            throw new IllegalStateException("source intent changed");
        }
        return this;
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("required text");
        }
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid hash");
    }
}
