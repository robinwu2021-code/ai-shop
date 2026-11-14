package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import java.math.BigDecimal;

/**
 * Quantity is always expressed in the published sales base unit; no implicit unit conversion or
 * rounding.
 */
public final class DiningMeasurementPolicy {
    private DiningMeasurementPolicy() {}

    public static boolean valid(MeasurementPolicy policy) {
        if (policy == null
                || !MaterialCatalogFacts.validKey(policy.unit(), 16)
                || policy.scale() == null
                || policy.allowedDeviation() != null && policy.allowedDeviation().signum() != 0)
            return false;
        return "COUNT".equals(policy.mode()) && policy.scale() == 0
                || "WEIGHT".equals(policy.mode()) && policy.scale() >= 1 && policy.scale() <= 6;
    }

    /** Existing COUNT/EA restaurant unit and inventory PIECE both denote one indivisible piece. */
    public static boolean sameBaseUnit(MeasurementPolicy sales, String stockUnit) {
        return sales.unit().equals(stockUnit)
                || "COUNT".equals(sales.mode())
                        && SourceQuantityUnits.same(sales.unit(), stockUnit);
    }

    public static MeasurementPolicy require(MeasurementPolicy policy) {
        if (!valid(policy)) throw BizException.of(ErrorCode.BAD_REQUEST);
        return policy;
    }

    public static void quantity(MeasurementPolicy policy, BigDecimal value) {
        require(policy);
        CartInputPolicy.quantity(value);
        if (value.stripTrailingZeros().scale() > policy.scale())
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
