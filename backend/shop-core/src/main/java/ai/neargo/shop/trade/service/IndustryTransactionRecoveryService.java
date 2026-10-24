package ai.neargo.shop.trade.service;

/** System recovery only queries original consented coordinates and never creates another payment instruction. */
public interface IndustryTransactionRecoveryService {
    record Batch(int claimed, int complete, int pending, int failed) { }
    Batch recoverDue(long now, int limit);
}
