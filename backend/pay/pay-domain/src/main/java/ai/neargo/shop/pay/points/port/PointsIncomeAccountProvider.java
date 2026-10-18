package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;

/**
 * Only certified host configuration supplies the platform income account; absence disables new
 * expiry intents.
 */
public interface PointsIncomeAccountProvider {
    FundsContext accountFor(String typeCode);
}
