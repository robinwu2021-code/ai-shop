package ai.neargo.commerce.core.spi;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;

import java.time.Instant;
import java.util.List;

/** Shared operation identity and account write guards for source and schedule mutations. */
public final class SubscriptionMutation {
    private SubscriptionMutation() {}

    public static String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new IllegalArgumentException("invalid business key");
        return key;
    }

    public static boolean claim(
            CommerceEventPersistencePort events,
            StoreRef store,
            String op,
            String kind,
            List<String> fields,
            Instant at) {
        return claim(events, store, op, kind, fields, at, "SUBSCRIPTION");
    }

    public static boolean claim(
            CommerceEventPersistencePort events,
            StoreRef store,
            String op,
            String kind,
            List<String> fields,
            Instant at,
            String producer) {
        return events.recordInbound(
                new InboundFact(
                        store.tenantNo(),
                        producer,
                        key(op),
                        kind,
                        store.storeNo(),
                        FactDigest.of(fields),
                        at));
    }

    public static void writable(AccountSnapshot account, long watermark, long at) {
        if (account.pendingChangeOrderNo() != null)
            throw new IllegalStateException("pending digital change");
        if (at < watermark) throw new IllegalArgumentException("backdated schedule change");
    }
}
