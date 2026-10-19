package ai.neargo.shop.pay.points.lifecycle;

import ai.neargo.shop.pay.points.model.PointType;

/**
 * Retirement releases the market slot only after every real obligation and money balance is clear.
 */
public interface TypeRetirementService {
    PointType retire(String typeCode, String actor);
}
