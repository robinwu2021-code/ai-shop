package ai.neargo.shop.common.pricing;

import java.util.List;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;

/** Stable machine evidence for explaining a rejection without exposing owner credentials or approvals. */
public record RuleViolation(String ruleNo, int ruleVersion, Stage stage, String code,
        List<String> sourceLineNos, List<AssetRef> assets) {
    public RuleViolation {
        sourceLineNos = List.copyOf(sourceLineNos);
        assets = List.copyOf(assets);
    }

    public enum Stage { CONFIGURATION, SELECTION, ELIGIBILITY, APPROVAL, AMOUNT }
}
