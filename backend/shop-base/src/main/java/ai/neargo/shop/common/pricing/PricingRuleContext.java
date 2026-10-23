package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;

/** Per-call immutable context; evaluator implementations keep no mutable state between requests. */
public record PricingRuleContext(PricingRuleFacts facts, EffectivePricingConfig config,
        PricingRuleCandidate candidate) {
    public PricingRuleContext {
        Objects.requireNonNull(facts);
        Objects.requireNonNull(config);
        Objects.requireNonNull(candidate);
    }

    public List<Benefit> effectiveBenefits() {
        return Stream.concat(facts.appliedActivities().stream(), candidate.benefits().stream()).toList();
    }
}
