package ai.neargo.shop.event.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Each implementation binds its own data source and short claim/ack transactions. */
public interface OutboxStore {
    String source();
    List<DeliveryRef> findDue(Instant now, int limit);
    Optional<ClaimedDelivery> tryClaim(DeliveryRef ref, String claimToken, Instant now, Duration lease);
    boolean complete(ClaimedDelivery claim, DeliveryResult result, Instant now, RetryPolicy policy);
    boolean renew(ClaimedDelivery claim, Instant now, Duration lease);
    int recoverExpired(Instant now, int limit);
}
