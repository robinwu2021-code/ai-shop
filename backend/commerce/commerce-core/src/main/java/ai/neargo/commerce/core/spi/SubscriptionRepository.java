package ai.neargo.commerce.core.spi;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;

import java.util.*;

/** Reads below are locking current reads within the shared write transaction. */
public interface SubscriptionRepository {
    void requireOuterTransaction(StoreRef store);

    void ensureAccount(StoreSnapshot store, String actor);

    AccountSnapshot lock(StoreRef store);

    List<Period> periods(StoreRef store);

    /** Current ordered NORMAL rows must match the latest accepted event snapshot for this code. */
    List<AccessSegment> segments(StoreRef store, String code);

    long lastChange(StoreRef store);

    void insert(AccountSnapshot account, Period period, long seq, String actor);

    void revise(
            StoreRef store,
            String code,
            String operation,
            long at,
            long revision,
            List<AccessSegment> before,
            List<AccessSegment> after,
            List<Period> periods,
            String actor);

    void updateVersion(AccountSnapshot account, long revision, String actor);

    /** Immutable original economics remain unchanged; only effective boundaries/status are cut. */
    default void cutSources(
            StoreRef store,
            String owningOrder,
            java.util.List<ConversionSource> sources,
            long effectiveAt) {
        throw new UnsupportedOperationException("conversion unavailable");
    }
    default void revokeZeroValue(StoreRef store,String sourceNo,SourceRevocation proof) {
        throw new UnsupportedOperationException("source revocation unavailable");
    }
}
