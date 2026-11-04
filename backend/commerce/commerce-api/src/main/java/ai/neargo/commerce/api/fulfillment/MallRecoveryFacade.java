package ai.neargo.commerce.api.fulfillment;

/**
 * Trusted scheduled maintenance only; no session/actor impersonation and no public HTTP exposure.
 */
public interface MallRecoveryFacade {
    record Batch(long afterOrderId, int limit) {}

    record Result(long nextOrderId, int visited, int failed, boolean endOfScan) {}

    Result recover(Batch batch);
}
