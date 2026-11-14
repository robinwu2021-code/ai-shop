package ai.neargo.commerce.core.fulfillment;

import java.util.List;

/**
 * Bounded immutable identifiers only. Every coordinator rechecks current state under its own locks.
 */
public interface RecoveryRepository {
    record Candidate(long id, String tenantNo, String orderNo) {}

    List<Candidate> orders(long afterId, int limit);

    List<String> pendingRefunds(String tenant, String order, int limit);
}
