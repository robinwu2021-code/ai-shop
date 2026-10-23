package ai.neargo.shop.common.pricing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.BenefitSelection.CouponOption;
import ai.neargo.shop.common.pricing.BenefitSelection.Mode;
import ai.neargo.shop.common.pricing.PlanSelectionResult.Rejection;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.PlanSelectionResult.Status;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;

/** Bounded, side-effect-free search orchestration. A real formula evaluator must be supplied by backend code. */
public final class BenefitPlanSelector {
    private static final Comparator<AssetRef> ASSET_ORDER = Comparator.comparing(AssetRef::owner)
            .thenComparing(AssetRef::assetNo);
    private final PricingRuleEngine rules = new PricingRuleEngine();

    /** Exhausts the admitted pool and allocation alternatives, or returns a failure with no partial winner. */
    public PlanSelectionResult select(Context context, BenefitSelection selection) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(selection);
        var state = new SearchState(context.limits().maxEvaluations());
        if (!validSelection(selection)) {
            state.reject("INVALID_SELECTION", selection.coupons(), List.of());
            return state.result(Status.INVALID_SELECTION, null);
        }
        boolean explicit = selection.mode() == Mode.EXPLICIT;
        int size = selection.coupons().size();
        if (!explicit && (size > context.limits().maxOptionalCoupons()
                || (1L << size) > context.limits().maxEvaluations())) {
            state.reject("PLAN_SEARCH_LIMIT", selection.coupons(), List.of());
            return state.result(Status.PLAN_SEARCH_LIMIT, null);
        }
        int combinations = explicit ? 1 : 1 << size;
        for (int mask = 0; mask < combinations; mask++) {
            var subset = new ArrayList<CouponOption>();
            for (int i = 0; i < size; i++) {
                if (explicit || (mask & (1 << i)) != 0) {
                    subset.add(selection.coupons().get(i));
                }
            }
            if (subset.size() + selection.fixedBenefits().size() > 64) {
                state.reject("CANDIDATE_CAPACITY_EXCEEDED", subset, List.of());
                continue;
            }
            var request = new PlanRequest(context.facts(), context.config(), subset, selection.fixedBenefits());
            Status failure = evaluate(context, request, state);
            if (failure != null) {
                return state.result(failure, null);
            }
        }
        if (state.best == null) {
            return state.result(Status.NO_LEGAL_PLAN, null);
        }
        var used = couponAssets(state.best);
        var unused = selection.coupons().stream().map(CouponOption::asset).filter(asset -> !used.contains(asset))
                .sorted(ASSET_ORDER).toList();
        var plan = new CalculationPlan(selection.mode(), context.facts(), context.config(), state.best, used, unused);
        return state.result(Status.SELECTED, plan);
    }

    private Status evaluate(Context context, PlanRequest request, SearchState state) {
        if (!state.consume()) {
            state.reject("PLAN_SEARCH_LIMIT", request.coupons(), List.of());
            return Status.PLAN_SEARCH_LIMIT;
        }
        state.subsets++;
        Evaluation evaluation = context.calculator().evaluate(request);
        if (evaluation == null) {
            state.reject("EVALUATOR_RETURNED_NO_RESULT", request.coupons(), List.of());
            return Status.EVALUATION_INCOMPLETE;
        }
        if (evaluation instanceof Unavailable unavailable) {
            state.reject(unavailable.code(), request.coupons(), List.of());
            return Status.EVALUATION_INCOMPLETE;
        }
        if (evaluation instanceof Rejected rejected) {
            state.reject(rejected.code(), request.coupons(), rejected.violations());
            return null;
        }
        var alternatives = ((Evaluated) evaluation).candidates();
        for (var candidate : alternatives) {
            if (!state.consume()) {
                state.reject("PLAN_SEARCH_LIMIT", request.coupons(), List.of());
                return Status.PLAN_SEARCH_LIMIT;
            }
            state.plans++;
            if (!matches(request, candidate)) {
                state.reject("EVALUATOR_INTENT_MISMATCH", request.coupons(), List.of());
                return Status.EVALUATION_INCOMPLETE;
            }
            var violations = rules.validate(request.facts(), request.config(), candidate);
            if (!violations.isEmpty()) {
                state.reject("RULE_CONFLICT", request.coupons(), violations);
                continue;
            }
            if (candidate.benefits().stream().anyMatch(benefit -> BenefitSelection.isCoupon(benefit.kind())
                    && benefit.amount().minor() == 0)) {
                state.reject("COUPON_HAS_NO_EFFECT", request.coupons(), List.of());
                continue;
            }
            if (state.best == null || compare(candidate, state.best) < 0) {
                state.best = candidate;
            }
        }
        return null;
    }

    private static boolean validSelection(BenefitSelection selection) {
        if (selection.mode() == Mode.EXPLICIT && selection.coupons().size() + selection.fixedBenefits().size() > 64) {
            return false;
        }
        var assets = new HashSet<AssetRef>();
        for (var coupon : selection.coupons()) {
            if (!assets.add(coupon.asset())) {
                return false;
            }
        }
        for (var fixed : selection.fixedBenefits()) {
            if (BenefitSelection.isCoupon(fixed.kind()) || fixed.kind() == BenefitKind.ACTIVITY
                    || !assets.add(fixed.asset())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(PlanRequest request, PricingRuleCandidate candidate) {
        var coupons = candidate.benefits().stream().filter(b -> BenefitSelection.isCoupon(b.kind()))
                .map(b -> new CouponOption(b.asset(), b.kind())).toList();
        var fixed = candidate.benefits().stream().filter(b -> !BenefitSelection.isCoupon(b.kind())).toList();
        return coupons.equals(request.coupons()) && fixed.equals(request.fixedBenefits());
    }

    private static List<AssetRef> couponAssets(PricingRuleCandidate candidate) {
        return candidate.benefits().stream().filter(b -> BenefitSelection.isCoupon(b.kind()))
                .map(Benefit::asset).sorted(ASSET_ORDER).toList();
    }

    private static int compare(PricingRuleCandidate left, PricingRuleCandidate right) {
        int amount = Long.compare(left.remainingPayable().minor(), right.remainingPayable().minor());
        if (amount != 0) {
            return amount;
        }
        var leftAssets = couponAssets(left);
        var rightAssets = couponAssets(right);
        int count = Integer.compare(leftAssets.size(), rightAssets.size());
        if (count != 0) {
            return count;
        }
        for (int i = 0; i < leftAssets.size(); i++) {
            int identity = ASSET_ORDER.compare(leftAssets.get(i), rightAssets.get(i));
            if (identity != 0) {
                return identity;
            }
        }
        return allocationKey(left).compareTo(allocationKey(right));
    }

    private static String allocationKey(PricingRuleCandidate candidate) {
        var portions = Comparator.comparing((PortionAmount a) -> a.portion().sourceLineNo())
                .thenComparing(a -> Objects.toString(a.portion().componentRef(), ""))
                .thenComparing(a -> a.portion().start()).thenComparing(a -> a.portion().end())
                .thenComparingLong(a -> a.amount().minor());
        var values = candidate.benefits().stream().sorted(Comparator.comparing(Benefit::asset, ASSET_ORDER))
                .map(b -> List.of(b.asset().owner(), b.asset().assetNo(), b.kind().name(), b.amount().minor(),
                        Objects.toString(b.payableRateBp(), ""), b.allocations().stream().sorted(portions)
                                .map(a -> List.of(a.portion().sourceLineNo(),
                                        Objects.toString(a.portion().componentRef(), ""),
                                        a.portion().start().toPlainString(), a.portion().end().toPlainString(),
                                        a.amount().minor())).toList())).toList();
        return PricingJson.JSON.writeValueAsString(values);
    }

    private static final class SearchState {
        private final int maximum;
        private int work;
        private int plans;
        private int subsets;
        private PricingRuleCandidate best;
        private final List<Rejection> rejections = new ArrayList<>();

        private SearchState(int maximum) {
            this.maximum = maximum;
        }

        private boolean consume() {
            if (work >= maximum) {
                return false;
            }
            work++;
            return true;
        }

        private void reject(String code, List<CouponOption> coupons, List<RuleViolation> violations) {
            rejections.add(new Rejection(code, coupons.stream().map(CouponOption::asset).sorted(ASSET_ORDER).toList(),
                    violations));
        }

        private PlanSelectionResult result(Status status, CalculationPlan plan) {
            return new PlanSelectionResult(status, plan, rejections, plans, subsets);
        }
    }

    public record Context(PricingRuleFacts facts, EffectivePricingConfig config,
            PlanCalculator calculator, SearchLimits limits) {
        public Context {
            Objects.requireNonNull(facts);
            Objects.requireNonNull(config);
            Objects.requireNonNull(calculator);
            Objects.requireNonNull(limits);
        }
    }

    public record SearchLimits(int maxOptionalCoupons, int maxEvaluations) {
        public SearchLimits {
            if (maxOptionalCoupons < 0 || maxOptionalCoupons > 12 || maxEvaluations < 2 || maxEvaluations > 4096) {
                throw new IllegalArgumentException("Search limits exceed bounded kernel capacity");
            }
        }

        public static SearchLimits standard() {
            return new SearchLimits(8, 1024);
        }
    }

    /** Re-evaluates exact intent, returning all relevant allocation alternatives; no reserve/redeem/IO is allowed. */
    @FunctionalInterface
    public interface PlanCalculator {
        Evaluation evaluate(PlanRequest request);
    }

    public record PlanRequest(PricingRuleFacts facts, EffectivePricingConfig config,
            List<CouponOption> coupons, List<Benefit> fixedBenefits) {
        public PlanRequest {
            Objects.requireNonNull(facts);
            Objects.requireNonNull(config);
            coupons = List.copyOf(coupons);
            fixedBenefits = List.copyOf(fixedBenefits);
        }
    }

    public sealed interface Evaluation permits Evaluated, Rejected, Unavailable {
    }

    /** Complete alternatives for this exact subset; use Unavailable when completeness cannot be established. */
    public record Evaluated(List<PricingRuleCandidate> candidates) implements Evaluation {
        public Evaluated {
            candidates = PricingRuleCandidate.bounded(candidates, 64);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("A complete evaluation needs candidates or an explicit rejection");
            }
        }
    }

    /** A definitive owner/formula ineligibility, as distinct from a missing or failed evaluation. */
    public record Rejected(String code, List<RuleViolation> violations) implements Evaluation {
        public Rejected {
            PricingPortion.requireIdentity(code, 64);
            violations = List.copyOf(violations);
        }
    }

    public record Unavailable(String code) implements Evaluation {
        public Unavailable {
            PricingPortion.requireIdentity(code, 64);
        }
    }
}
