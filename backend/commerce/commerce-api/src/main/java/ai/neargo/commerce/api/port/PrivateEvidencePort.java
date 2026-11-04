package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

/** Existing private byte-storage authority. Invoked only outside commerce transactions. */
public interface PrivateEvidencePort {
    record Scope(StoreRef store, String entityNo, String orderNo, SubjectKind submitterKind,
            String submitterNo, String purpose) {}
    boolean stored(Scope scope, String assetKey);
    String signedRead(Scope scope, String assetKey);
}
