package ai.neargo.shop.pay.settle;

/** Internal replenishment runs outside the export transaction using an already committed points ticket. */
public interface SettlementPointsFundingExecutionService {
    boolean ensureReady(String settleNo);
}
