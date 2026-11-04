package ai.neargo.commerce.api.port;

/**
 * Trusted inventory authority. Commands are idempotent by tenant/component/operationNo and
 * immutable SKU/quantity. Reserve and ship queries return evidence for that exact operation, never
 * infer it from aggregate quantities. Release targets the original reserve operation and SUCCEEDED
 * with zero aggregate quantities proves a durable fence rejecting any concurrent/later reserve
 * replay. UNKNOWN or NOT_FOUND never proves a release. No network call may run inside a commerce
 * transaction.
 */
public interface HardwareInventoryPort {
    InventoryOutcome reserve(InventoryCommand command);

    InventoryOutcome query(InventoryQuery query);

    InventoryOutcome release(InventoryQuery query);

    InventoryOutcome ship(InventoryCommand command);
}
