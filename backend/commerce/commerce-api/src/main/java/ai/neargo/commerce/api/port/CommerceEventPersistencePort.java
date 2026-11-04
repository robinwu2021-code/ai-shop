package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;

/**
 * append and inbound dedup join the caller local DataSource transaction; eventNo/fact identity is
 * unique. No external calls while holding commerce locks.
 */
public interface CommerceEventPersistencePort {
    boolean append(CommerceEvent event);

    /**
     * Insert or verify an immutable fact in the caller's identical local transaction. Returns true
     * only for a newly inserted fact, false only after current verification of the SAME immutable
     * producer/fact/type/aggregate/digest. Custom adapters must preserve these semantics, avoid
     * independent transactions, and throw InboundFactConflictException for mismatched identity.
     * Duplicate immutable verification may share its row lock; it must not rewrite the original
     * fact.
     */
    boolean recordInbound(InboundFact fact);

    EventPage pending(EventQuery query);

    void markPublished(String tenantNo, String eventNo, Instant publishedAt);
}
