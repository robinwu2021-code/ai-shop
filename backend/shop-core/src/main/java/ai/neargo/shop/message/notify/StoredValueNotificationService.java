package ai.neargo.shop.message.notify;

import java.util.Map;

/** Durable notification workflow, independent of all financial write commands. */
public interface StoredValueNotificationService {
    /** Captures eligibility and reserves a single authorization now; later opt-in cannot backfill history. */
    void enqueue(String tenant, String eventNo, String usageNo, String recipient, String storeNo,
                 String documentNo, Map<String, String> values);
    /** Bounded worker pass; includes stale in-flight recovery to UNKNOWN without resending. */
    int dispatchDue();
    /** Exposes only a genuine approved template for the configured application. */
    Capability capability();
    record Capability(String channel, boolean available, String templateId, String reasonCode) { }
}
