package ai.neargo.shop.pay.points.pool.withdrawal;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** Original bank target is frozen before dispatch; result never replaces or edits the command. */
public record WithdrawalSnapshot(PoolRequest request,FundsCommand fundsCommand,FundsResult result) { }
