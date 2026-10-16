package ai.neargo.shop.pay.points.lifecycle;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** Executes or queries the immutable expiry income intent outside the ledger transaction. */
public interface ExpiryExecutionService {
    FundsResult execute(String ticketNo);
}
