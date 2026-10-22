package ai.neargo.shop.common.pricing;

import java.util.Map;

/** Code-owned versioned formulas, never a store-editable expression or a runtime class name. */
public final class FormulaRegistry {
    private static final Map<String, PricingFormula> V1 = Map.of(
            "DEDUCT_THEN_DISCOUNT_V1", new FixedPricingFormula("DEDUCT_THEN_DISCOUNT_V1"),
            "DEDUCT_THEN_STORED_VALUE_V1", new FixedPricingFormula("DEDUCT_THEN_STORED_VALUE_V1"),
            "LEGACY_OWNER_COMPAT", new FixedPricingFormula("LEGACY_OWNER_COMPAT"));

    private FormulaRegistry() {
    }

    public static PricingFormula resolve(String formulaId, int version) {
        var formula = V1.get(formulaId);
        if (version != 1 || formula == null) {
            throw new PricingFormula.Failure(PricingResult.Status.UNAVAILABLE, "FORMULA_UNSUPPORTED");
        }
        return formula;
    }
}
