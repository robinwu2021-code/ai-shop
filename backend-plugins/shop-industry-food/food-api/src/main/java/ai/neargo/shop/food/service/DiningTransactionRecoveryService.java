package ai.neargo.shop.food.service;

/** Backend source projection only; never creates a new order, payment, capture or refund key. */
public interface DiningTransactionRecoveryService {
    record Report(int claimed, int complete, int pending, int failed) { }
    Report recoverDue(int limit);
}
