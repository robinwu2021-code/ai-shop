package ai.neargo.shop.food.service;

/** Local primary-transaction guard shared by cart, original order changes and business topology. */
public interface CheckBusinessGuardService {
    void assertPartyMutable(String entity, String store, String check);

    void lockBatchChecks(String entity, String store, String batch);

    void invalidateBatchShares(
            String entity, String store, String batch, String actor, String reason);

    void assertNoPendingChanges(String entity, String store, String check);

    void assertMutable(String entity, String store, String check);

    void invalidateShares(String entity, String store, String check, String actor, String reason);
}
