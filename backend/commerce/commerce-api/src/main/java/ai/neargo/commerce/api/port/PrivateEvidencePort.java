package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;
import java.io.InputStream;
import java.util.function.Supplier;

/**
 * Existing private byte-storage authority. {@link #newKey} is pure and may run inside a commerce
 * transaction; every other call happens outside commerce transactions.
 */
public interface PrivateEvidencePort {
    record Scope(StoreRef store, String entityNo, String orderNo, SubjectKind submitterKind,
            String submitterNo, String purpose) {}
    /** Bytes for one reserved key. The supplier must return a fresh stream on every call. */
    record Upload(Supplier<InputStream> content, long size, String contentType) {}
    /** Deterministic storage key for a reservation; no IO. Hosts override to their private layout. */
    default String newKey(Scope scope, String uniqueNo) { return "mall-private/" + uniqueNo + ".jpg"; }
    /** True only when bytes exist and the host's record binds them to exactly this scope. */
    boolean stored(Scope scope, String assetKey);
    String signedRead(Scope scope, String assetKey);
    /** Stores bytes once under a reserved key bound to the scope. */
    default void store(Scope scope, String assetKey, Upload upload) { throw new UnsupportedOperationException(); }
}
