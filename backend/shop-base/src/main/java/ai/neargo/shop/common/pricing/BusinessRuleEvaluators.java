package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.CombinationMode;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.OverlapMode;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.RuleDefinition.EvaluatorType;
import ai.neargo.shop.common.pricing.RuleDefinition.ForbiddenCombination;
import ai.neargo.shop.common.pricing.RuleViolation.Stage;

/** Fixed pre-tax business handlers; they validate proposed amounts without calculating discount formulas. */
final class BusinessRuleEvaluators {
    private static final Set<BenefitKind> COUPONS = Set.of(BenefitKind.PERCENT_COUPON,
            BenefitKind.CASH_COUPON, BenefitKind.DEDUCTION_COUPON);
    private static final Set<BenefitKind> MANUAL = Set.of(BenefitKind.MANUAL_PERCENT,
            BenefitKind.MANUAL_AMOUNT, BenefitKind.MANUAL_WAIVER, BenefitKind.CASH_ROUNDING);

    private BusinessRuleEvaluators() {
    }

    static List<RuleEvaluator> standard() {
        return List.of(new Handler(EvaluatorType.FORBID_COMBINATION, BusinessRuleEvaluators::forbid),
                new Handler(EvaluatorType.COUNT_LIMIT, BusinessRuleEvaluators::counts),
                new Handler(EvaluatorType.REQUIRE_CAPABILITY, BusinessRuleEvaluators::capabilities),
                new Handler(EvaluatorType.SCOPE_OVERLAP, BusinessRuleEvaluators::overlap),
                new Handler(EvaluatorType.ORDER_AND_REMAINDER, BusinessRuleEvaluators::stages),
                new Handler(EvaluatorType.REQUIRE_APPROVAL, BusinessRuleEvaluators::approvals),
                new Handler(EvaluatorType.CONSERVATION, BusinessRuleEvaluators::conservation));
    }

    private static List<RuleViolation> forbid(PricingRuleContext c, RuleDefinition rule) {
        var parameters = (ForbiddenCombination) rule.parameters();
        var left = c.effectiveBenefits().stream().filter(b -> parameters.leftKinds().contains(b.kind())).toList();
        var right = c.effectiveBenefits().stream().filter(b -> parameters.rightKinds().contains(b.kind())).toList();
        String code = switch (rule.ruleNo()) {
            case "B01" -> "ACTIVITY_COUPON_CONFLICT";
            case "B02" -> "PERCENT_STORED_CONFLICT";
            case "B07" -> "MANUAL_BENEFIT_CONFLICT";
            case "B08" -> "COUPON_MANUAL_CONFLICT";
            default -> throw new IllegalArgumentException("Unregistered prohibition");
        };
        return left.isEmpty() || right.isEmpty() ? List.of()
                : List.of(issue(rule, Stage.SELECTION, code, joined(left, right)));
    }

    private static List<RuleViolation> counts(PricingRuleContext c, RuleDefinition rule) {
        var uses = new HashMap<AssetRef, BenefitKind>();
        c.facts().previousCoupons().forEach(use -> uses.putIfAbsent(use.asset(), use.kind()));
        c.candidate().benefits().stream().filter(b -> COUPONS.contains(b.kind()))
                .forEach(b -> uses.putIfAbsent(b.asset(), b.kind()));
        long percent = uses.values().stream().filter(k -> k == BenefitKind.PERCENT_COUPON).count();
        long cash = uses.values().stream().filter(k -> k == BenefitKind.CASH_COUPON).count();
        long deduction = uses.values().stream().filter(k -> k == BenefitKind.DEDUCTION_COUPON).count();
        var config = c.config().definition();
        var limits = config.couponLimits();
        boolean invalid = rule.ruleNo().equals("B04")
                ? percent > limits.percent() || percent > config.discountCouponPolicy().maxUsedPerOrder()
                        || uses.size() > limits.total()
                : cash > limits.cash() || deduction > limits.deduction()
                        || cash + deduction > limits.amountTotal() || uses.size() > limits.total();
        if (!invalid) {
            return List.of();
        }
        var current = c.candidate().benefits().stream().filter(b -> COUPONS.contains(b.kind())).toList();
        var evidence = issue(rule, Stage.SELECTION, "ROOT_COUPON_LIMIT_EXCEEDED", current);
        return List.of(new RuleViolation(evidence.ruleNo(), evidence.ruleVersion(), evidence.stage(), evidence.code(),
                evidence.sourceLineNos(),
                uses.keySet().stream().sorted(BusinessRuleEvaluators::compareAsset).toList()));
    }

    private static List<RuleViolation> capabilities(PricingRuleContext c, RuleDefinition rule) {
        var result = new ArrayList<RuleViolation>();
        var benefits = c.effectiveBenefits();
        Set<BenefitKind> kinds = benefits.stream().map(Benefit::kind).collect(Collectors.toSet());
        if (rule.ruleNo().equals("B09")) {
            if (kinds.contains(BenefitKind.STORED_VALUE)) {
                var companions = benefits.stream().filter(b -> b.kind() == BenefitKind.ACTIVITY
                        || b.kind() == BenefitKind.CASH_COUPON || b.kind() == BenefitKind.DEDUCTION_COUPON).toList();
                for (var other : companions) {
                    for (var stored : benefits.stream().filter(b -> b.kind() == BenefitKind.STORED_VALUE).toList()) {
                        if (!allows(c, stored, other.kind()) || !allows(c, other, stored.kind())) {
                            result.add(issue(rule, Stage.ELIGIBILITY, "STORED_COMBINATION_UNVERIFIED",
                                    List.of(stored, other)));
                        }
                    }
                }
            }
            return result;
        }
        var definition = c.config().definition();
        Set<BenefitKind> coupons = kinds.stream().filter(COUPONS::contains).collect(Collectors.toSet());
        boolean combinationAllowed = coupons.size() <= 1 || definition.allowedCouponCombinations().stream()
                .anyMatch(combination -> new HashSet<>(combination).equals(coupons));
        if (!combinationAllowed || definition.benefitCombinationMode() == CombinationMode.SINGLE_KIND
                && kinds.size() > 1 || kinds.contains(BenefitKind.STORED_VALUE) && !definition.storedValueEnabled()
                || kinds.contains(BenefitKind.POINTS) && !definition.pointsEnabled()) {
            result.add(issue(rule, Stage.ELIGIBILITY, "CONFIGURATION_COMBINATION_DISABLED", benefits));
        }
        for (var benefit : benefits) {
            if (MANUAL.contains(benefit.kind())) {
                continue;
            }
            var owner = owner(c, benefit);
            boolean valid = owner != null && owner.formulaId().equals(definition.formulaId())
                    && owner.formulaVersion() == definition.formulaVersion()
                    && owner.calculationBasis().equals(c.facts().context().calculationBasis())
                    && benefits.stream().filter(other -> !other.asset().equals(benefit.asset()))
                            .allMatch(other -> owner.combinableKinds().contains(other.kind()));
            if (!valid) {
                result.add(issue(rule, Stage.ELIGIBILITY, "OWNER_BASIS_OR_COMBINATION_UNVERIFIED", List.of(benefit)));
            }
        }
        return result;
    }

    private static List<RuleViolation> overlap(PricingRuleContext c, RuleDefinition rule) {
        var percent = c.candidate().benefits().stream().filter(b -> b.kind() == BenefitKind.PERCENT_COUPON).toList();
        var policy = c.config().definition().discountCouponPolicy();
        int limit = policy.overlapMode() == OverlapMode.STACK ? policy.maxUsedPerItem() : 1;
        for (var benefit : percent) {
            for (var portion : benefit.portions()) {
                long depth = percent.stream().filter(other -> other.portions().stream().anyMatch(scope ->
                        scope.sameComponent(portion) && scope.start().compareTo(portion.start()) <= 0
                                && scope.end().compareTo(portion.start()) > 0)).map(Benefit::asset).distinct().count();
                if (depth > limit) {
                    return List.of(issue(rule, Stage.SELECTION, "PERCENT_SCOPE_OVERLAP", percent));
                }
            }
        }
        return List.of();
    }

    private static List<RuleViolation> stages(PricingRuleContext c, RuleDefinition rule) {
        var result = new ArrayList<RuleViolation>();
        int previous = 0;
        for (var benefit : c.candidate().benefits()) {
            int current = stage(benefit.kind());
            if (current < previous) {
                result.add(issue(rule, Stage.SELECTION, "INVALID_STAGE_ORDER", List.of(benefit)));
            }
            previous = Math.max(previous, current);
            if (current == 1 || current == 2 || current == 3) {
                var owner = owner(c, benefit);
                if (owner == null || !owner.eligibleRemainder().currency().equals(benefit.amount().currency())
                        || benefit.amount().minor() > owner.eligibleRemainder().minor()) {
                    result.add(issue(rule, Stage.AMOUNT, "OWNER_REMAINDER_EXCEEDED", List.of(benefit)));
                }
            }
        }
        return result;
    }

    private static List<RuleViolation> approvals(PricingRuleContext c, RuleDefinition rule) {
        var result = new ArrayList<RuleViolation>();
        var manual = c.config().definition().manualOperations();
        var access = c.facts().context();
        for (var benefit : c.candidate().benefits()) {
            if (!MANUAL.contains(benefit.kind())) {
                continue;
            }
            boolean matched = manual.enabled() && (benefit.kind() != BenefitKind.CASH_ROUNDING
                    || manual.cashRoundingEnabled()) && c.candidate().approvals().stream().anyMatch(approval ->
                    approval.asset().equals(benefit.asset()) && approval.kind() == benefit.kind()
                            && approval.actorNo().equals(access.actorNo())
                            && approval.storeNo().equals(access.storeNo())
                            && approval.scopeNo().equals(access.scopeNo()) && approval.amount().equals(benefit.amount())
                            && approval.expiresAt().isAfter(access.evaluatedAt())
                            && manual.reasonCategories().contains(approval.reasonCode()));
            if (!matched) {
                result.add(issue(rule, Stage.APPROVAL, "MANUAL_APPROVAL_REQUIRED", List.of(benefit)));
            }
            if (!manual.amountBasis().name().equals(access.calculationBasis())) {
                result.add(issue(rule, Stage.APPROVAL, "MANUAL_BASIS_UNVERIFIED", List.of(benefit)));
            }
            if (benefit.kind() == BenefitKind.MANUAL_PERCENT && (benefit.payableRateBp() == null
                    || manual.minPayableRateBp() != null && benefit.payableRateBp() < manual.minPayableRateBp())) {
                result.add(issue(rule, Stage.APPROVAL, "MANUAL_RATE_BELOW_FLOOR", List.of(benefit)));
            }
        }
        if (manual.minPayableRateBp() != null) {
            BigInteger manualAmount = c.candidate().benefits().stream().filter(b -> MANUAL.contains(b.kind()))
                    .map(b -> BigInteger.valueOf(b.amount().minor())).reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger base = BigInteger.valueOf(c.facts().prices().baseTotal().minor());
            if (base.subtract(manualAmount).multiply(BigInteger.valueOf(10000))
                    .compareTo(base.multiply(BigInteger.valueOf(manual.minPayableRateBp()))) < 0) {
                result.add(issue(rule, Stage.APPROVAL, "MANUAL_RATE_BELOW_FLOOR", c.candidate().benefits()));
            }
        }
        return result;
    }

    private static List<RuleViolation> conservation(PricingRuleContext c, RuleDefinition rule) {
        var result = new ArrayList<RuleViolation>();
        var benefits = c.effectiveBenefits();
        var assets = new HashSet<AssetRef>();
        var historical = new HashMap<AssetRef, BenefitKind>();
        for (var use : c.facts().previousCoupons()) {
            var previous = historical.putIfAbsent(use.asset(), use.kind());
            if (previous != null && previous != use.kind()) {
                result.add(issue(rule, Stage.ELIGIBILITY, "ASSET_KIND_MISMATCH", benefits));
            }
        }
        if (c.candidate().benefits().stream().anyMatch(b -> b.kind() == BenefitKind.ACTIVITY)) {
            result.add(issue(rule, Stage.ELIGIBILITY, "ACTIVITY_REQUIRES_SOURCE_FACT", benefits));
        }
        String currency = c.facts().prices().currency();
        BigInteger reductions = BigInteger.ZERO;
        var remainingByPortion = new HashMap<PricingPortion, BigInteger>();
        c.facts().prices().units().forEach(unit -> remainingByPortion.put(PricingPortion.from(unit),
                BigInteger.valueOf(unit.baseAmount().minor())));
        BigInteger excluded = c.facts().prices().units().stream().filter(unit -> c.facts().unitPolicies().stream()
                .anyMatch(policy -> policy.portion().equals(PricingPortion.from(unit)) && !policy.promotionEligible()))
                .map(unit -> BigInteger.valueOf(unit.baseAmount().minor())).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger priceReductions = BigInteger.ZERO;
        for (var benefit : benefits) {
            if (benefit.kind() != BenefitKind.STORED_VALUE && benefit.kind() != BenefitKind.POINTS) {
                priceReductions = priceReductions.add(BigInteger.valueOf(benefit.amount().minor()));
            }
            for (var allocation : benefit.allocations()) {
                var remaining = remainingByPortion.get(allocation.portion());
                if (remaining == null) {
                    result.add(issue(rule, Stage.ELIGIBILITY, "SCOPE_REQUIRES_PRICED_PORTION", List.of(benefit)));
                } else {
                    remaining = remaining.subtract(BigInteger.valueOf(allocation.amount().minor()));
                    remainingByPortion.put(allocation.portion(), remaining);
                    if (remaining.signum() < 0) {
                        result.add(issue(rule, Stage.AMOUNT, "SCOPE_AMOUNT_EXCEEDED", List.of(benefit)));
                    }
                }
            }
            if (!assets.add(benefit.asset())) {
                result.add(issue(rule, Stage.SELECTION, "DUPLICATE_ASSET", List.of(benefit)));
            }
            if (historical.containsKey(benefit.asset()) && historical.get(benefit.asset()) != benefit.kind()) {
                result.add(issue(rule, Stage.SELECTION, "ASSET_KIND_MISMATCH", List.of(benefit)));
            }
            if (!currency.equals(benefit.amount().currency())) {
                result.add(issue(rule, Stage.AMOUNT, "CURRENCY_MISMATCH", List.of(benefit)));
            }
            reductions = reductions.add(BigInteger.valueOf(benefit.amount().minor()));
            if (!validScope(c, benefit)) {
                result.add(issue(rule, Stage.ELIGIBILITY, "INVALID_BENEFIT_SCOPE", List.of(benefit)));
            }
        }
        if (BigInteger.valueOf(c.facts().prices().baseTotal().minor()).subtract(priceReductions)
                .compareTo(excluded) < 0) {
            result.add(issue(rule, Stage.AMOUNT, "EXCLUDED_AMOUNT_NOT_PRESERVED", benefits));
        }
        if (!currency.equals(c.candidate().remainingPayable().currency())) {
            result.add(issue(rule, Stage.AMOUNT, "CURRENCY_MISMATCH", benefits));
        }
        BigInteger conserved = reductions.add(BigInteger.valueOf(c.candidate().remainingPayable().minor()));
        if (!conserved.equals(BigInteger.valueOf(c.facts().prices().baseTotal().minor()))) {
            result.add(issue(rule, Stage.AMOUNT, "AMOUNT_NOT_CONSERVED", benefits));
        }
        var groups = new HashMap<String, Set<PricingRole>>();
        for (var policy : c.facts().unitPolicies()) {
            if (policy.status() != ScopeStatus.OPEN) {
                result.add(new RuleViolation(rule.ruleNo(), rule.ruleVersion(), Stage.ELIGIBILITY,
                        "SOURCE_SCOPE_NOT_OPEN", List.of(policy.portion().sourceLineNo()), List.of()));
            }
            if (policy.bundleGroup() != null) {
                groups.computeIfAbsent(policy.bundleGroup(), ignored -> new HashSet<>()).add(policy.role());
            }
        }
        if (groups.values().stream().anyMatch(roles -> roles.contains(PricingRole.PARENT)
                && roles.contains(PricingRole.LEAF))) {
            result.add(new RuleViolation(rule.ruleNo(), rule.ruleVersion(), Stage.ELIGIBILITY,
                    "BUNDLE_PARENT_LEAF_DOUBLE_COUNT", c.facts().unitPolicies().stream()
                            .filter(policy -> policy.bundleGroup() != null)
                            .map(policy -> policy.portion().sourceLineNo())
                            .distinct().sorted().toList(), List.of()));
        }
        if (duplicateEvidence(c)) {
            result.add(issue(rule, Stage.ELIGIBILITY, "DUPLICATE_EVIDENCE", benefits));
        }
        return result;
    }

    private static boolean validScope(PricingRuleContext c, Benefit benefit) {
        boolean funding = benefit.kind() == BenefitKind.STORED_VALUE || benefit.kind() == BenefitKind.POINTS;
        for (int i = 0; i < benefit.portions().size(); i++) {
            var portion = benefit.portions().get(i);
            for (int j = 0; j < i; j++) {
                if (portion.overlaps(benefit.portions().get(j))) {
                    return false;
                }
            }
            BigDecimal covered = BigDecimal.ZERO;
            for (var policy : c.facts().unitPolicies()) {
                if (portion.overlaps(policy.portion())) {
                    if (policy.status() != ScopeStatus.OPEN || !funding && !policy.promotionEligible()) {
                        return false;
                    }
                    covered = covered.add(portion.end().min(policy.portion().end())
                            .subtract(portion.start().max(policy.portion().start())));
                }
            }
            if (covered.compareTo(portion.end().subtract(portion.start())) != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean duplicateEvidence(PricingRuleContext c) {
        return c.candidate().owners().stream().map(OwnerEvidence::asset).distinct().count()
                        != c.candidate().owners().size()
                || c.candidate().approvals().stream().map(a -> a.asset()).distinct().count()
                        != c.candidate().approvals().size();
    }

    private static int stage(BenefitKind kind) {
        return switch (kind) {
            case STORED_VALUE -> 1;
            case POINTS -> 2;
            case MANUAL_WAIVER -> 3;
            case CASH_ROUNDING -> 4;
            default -> 0;
        };
    }

    private static OwnerEvidence owner(PricingRuleContext c, Benefit benefit) {
        return c.candidate().owners().stream().filter(e -> e.asset().equals(benefit.asset())).findFirst().orElse(null);
    }

    private static boolean allows(PricingRuleContext c, Benefit benefit, BenefitKind other) {
        var evidence = owner(c, benefit);
        return evidence != null && evidence.combinableKinds().contains(other);
    }

    private static List<Benefit> joined(List<Benefit> left, List<Benefit> right) {
        var values = new ArrayList<>(left);
        values.addAll(right);
        return values;
    }

    static RuleViolation issue(RuleDefinition rule, Stage stage, String code, List<Benefit> benefits) {
        var lines = benefits.stream().flatMap(b -> b.portions().stream()).map(PricingPortion::sourceLineNo)
                .distinct().sorted().toList();
        var assets = benefits.stream().map(Benefit::asset).distinct()
                .sorted(BusinessRuleEvaluators::compareAsset).toList();
        return new RuleViolation(rule.ruleNo(), rule.ruleVersion(), stage, code, lines, assets);
    }

    private static int compareAsset(AssetRef a, AssetRef b) {
        int owner = a.owner().compareTo(b.owner());
        return owner == 0 ? a.assetNo().compareTo(b.assetNo()) : owner;
    }

    private record Handler(EvaluatorType type,
            BiFunction<PricingRuleContext, RuleDefinition, List<RuleViolation>> evaluation) implements RuleEvaluator {
        @Override
        public List<RuleViolation> evaluate(PricingRuleContext context, RuleDefinition definition) {
            return evaluation.apply(context, definition);
        }
    }
}
