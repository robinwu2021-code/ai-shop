package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Only trusted backend code supplies system constraints; no corresponding request JSON is accepted. */
public final class SystemConstraints {
    private static final SystemConstraints STANDARD = new SystemConstraints();

    private SystemConstraints() {
    }

    public static SystemConstraints standard() {
        return STANDARD;
    }

    public List<RuleDefinition> rules() {
        return PricingRuleCatalog.standardRules();
    }

    public Set<String> mandatoryRuleNos() {
        return rules().stream().map(RuleDefinition::ruleNo).collect(Collectors.toUnmodifiableSet());
    }

    String semanticHash() {
        return PricingRuleCatalog.semanticHash();
    }
}
