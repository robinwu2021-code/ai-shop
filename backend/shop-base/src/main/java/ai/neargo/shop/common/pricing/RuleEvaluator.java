package ai.neargo.shop.common.pricing;

import java.util.List;
import ai.neargo.shop.common.pricing.RuleDefinition.EvaluatorType;

/** Finite code-owned handlers; configuration never supplies an implementation class or a script. */
public interface RuleEvaluator {
    EvaluatorType type();
    List<RuleViolation> evaluate(PricingRuleContext context, RuleDefinition definition);
}
