package ai.neargo.shop.common.pricing;

import java.util.List;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Immutable compilation output. Valid structure/capability evidence is not a transaction authorization. */
public record EffectivePricingConfig(
        Definition definition, List<RuleDefinition> rules, String canonicalJson, String configHash) {
    public EffectivePricingConfig {
        rules = List.copyOf(rules);
    }

    public enum CombinationMode { MULTI_KIND, SINGLE_KIND }
    public enum CouponMode { SINGLE, MULTI }
    public enum OverlapMode { DISJOINT, BEST_ONE, STACK }
    public enum AmountBasis { PAYABLE_GROSS, TAX_EXCLUSIVE }

    public enum IndustryRestriction {
        PUBLISHED_MENU_CHANNEL_PRICE_ONLY, BUNDLE_PARENT_OR_LEAF_PRICING,
        ROOT_CHECKOUT_SCOPE_COUPON_LIMIT, FROZEN_POSTPAID_BATCHES,
        LEGACY_RETAIL_NOT_AUTO_MIGRATED, ORIGINAL_FUNDER_ALLOCATION,
        OFFLINE_PLATFORM_COUPON_RESTRICTIONS, FREIGHT_ELIGIBILITY_SEPARATE,
        ORIGINAL_SERVICE_SOURCE, RIGHTS_SEPARATE_FROM_MONEY, STORED_REDEMPTION_FULL_COVER_ONLY,
        NO_AUTOMATIC_STORED_CASH_MIX, NO_NEW_ONLINE_STORED_REVERSAL
    }

    public record Definition(
            int schemaVersion, CombinationMode benefitCombinationMode, CouponMode couponMode,
            CouponLimits couponLimits, DiscountCouponPolicy discountCouponPolicy,
            List<List<BenefitKind>> allowedCouponCombinations, String activityAndCoupons,
            boolean storedValueEnabled, boolean pointsEnabled, boolean pointsAutoApply,
            String pointsStage, String optionalCapabilityPolicy, String formulaId, int formulaVersion,
            ManualOperations manualOperations, TaxBinding taxBinding, List<String> ruleRefs,
            List<IndustryRestriction> industryRestrictions) {
        public Definition {
            allowedCouponCombinations = allowedCouponCombinations.stream().map(List::copyOf).toList();
            ruleRefs = List.copyOf(ruleRefs);
            industryRestrictions = List.copyOf(industryRestrictions);
        }
    }

    public record CouponLimits(int total, int percent, int amountTotal, int cash, int deduction) {
    }

    public record DiscountCouponPolicy(
            int maxUsedPerOrder, OverlapMode overlapMode, int maxUsedPerItem, boolean stackingEnabled) {
    }

    public record ManualOperations(
            boolean enabled, List<String> reasonCategories, ApprovalLimits limits, AmountBasis amountBasis,
            String manualAmountConflictPolicy, boolean cashRoundingEnabled, Integer minPayableRateBp) {
        public ManualOperations {
            reasonCategories = List.copyOf(reasonCategories);
        }
    }

    public record ApprovalLimits(String source, String missingPolicy) {
    }

    public record TaxBinding(
            String taxProfileNo, String resolveFrom, String missingPolicy, boolean storeMayEditRate) {
    }
}
