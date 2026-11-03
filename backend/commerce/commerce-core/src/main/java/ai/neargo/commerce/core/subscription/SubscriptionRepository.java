package ai.neargo.commerce.core.subscription;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.spi.*;

import java.util.*;

/** Reads below are locking current reads within the shared write transaction. */
public interface SubscriptionRepository {
    void requireOuterTransaction(StoreRef store);

    void ensureAccount(StoreSnapshot store, String actor);

    AccountSnapshot lock(StoreRef store);

    List<Period> periods(StoreRef store);

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
}
