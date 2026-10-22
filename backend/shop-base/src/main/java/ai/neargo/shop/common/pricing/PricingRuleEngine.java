package ai.neargo.shop.common.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import ai.neargo.shop.common.pricing.RuleDefinition.EvaluatorType;
import ai.neargo.shop.common.pricing.RuleViolation.Stage;

/** Business-stage validation only. An empty result neither executes tax rules nor authorizes asset consumption. */
public final class PricingRuleEngine {
    private static final Map<EvaluatorType, RuleEvaluator> EVALUATORS = BusinessRuleEvaluators.standard().stream()
            .collect(Collectors.toUnmodifiableMap(RuleEvaluator::type, Function.identity()));

    public List<RuleViolation> validate(PricingRuleFacts facts, EffectivePricingConfig config,
            PricingRuleCandidate candidate) {
        var context = new PricingRuleContext(facts, config, candidate);
        var compiled = new PricingConfigCompiler().compile(config.canonicalJson(), "{}", SystemConstraints.standard(),
                new PricingCapabilities(64, 64, true, true, true));
        if (!compiled.valid() || !compiled.config().equals(config)) {
            return List.of(new RuleViolation("B12", 1, Stage.CONFIGURATION, "CONFIG_NOT_COMPILED",
                    List.of(), List.of()));
        }
        var issues = new ArrayList<RuleViolation>();
        for (var definition : config.rules()) {
            if (definition.ruleNo().startsWith("B")) {
                var evaluator = EVALUATORS.get(definition.evaluatorType());
                if (evaluator == null) {
                    throw new IllegalStateException("Mandatory business evaluator is missing");
                }
                issues.addAll(evaluator.evaluate(context, definition));
            }
        }
        return List.copyOf(issues);
    }
}
