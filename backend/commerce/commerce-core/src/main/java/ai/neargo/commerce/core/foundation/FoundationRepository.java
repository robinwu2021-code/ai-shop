package ai.neargo.commerce.core.foundation;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.*;

import java.util.*;

/** The subscription account is locked before loading sources and the accepted per-end schedules. */
public interface FoundationRepository {
    record GrantState(String endCode, boolean revoked) {}

    AccountSnapshot lock(StoreRef store);

    List<Period> periods(StoreRef store);

    Map<String, GrantState> grantStates(StoreRef store);

    List<AccessSegment> segments(StoreRef store, String code);
}
