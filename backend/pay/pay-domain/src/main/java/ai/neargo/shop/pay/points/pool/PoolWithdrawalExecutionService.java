package ai.neargo.shop.pay.points.pool;

/** Queries unknown outgoing operations using the original target and provider idempotency key. */
public interface PoolWithdrawalExecutionService {
    PoolWithdrawalService.Outcome execute(String ticketNo);
}
