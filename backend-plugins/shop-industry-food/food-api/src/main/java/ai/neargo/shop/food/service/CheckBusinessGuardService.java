package ai.neargo.shop.food.service;

/** Local primary-transaction guard shared by cart, original order changes and business topology. */
public interface CheckBusinessGuardService {
    void assertPartyMutable(String entity, String store, String check);

    /** 释放服务的前置：OPEN 台账照「可修改」；柜台结账已关单（CLOSED）的也允许，只要服务未释放、无冻结与服务单。 */
    void assertReleasable(String entity, String store, String check);

    void lockBatchChecks(String entity, String store, String batch);

    void invalidateBatchShares(
            String entity, String store, String batch, String actor, String reason);

    void assertNoPendingChanges(String entity, String store, String check);

    void assertMutable(String entity, String store, String check);

    void invalidateShares(String entity, String store, String check, String actor, String reason);
}
