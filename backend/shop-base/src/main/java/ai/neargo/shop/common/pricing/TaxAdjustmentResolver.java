package ai.neargo.shop.common.pricing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.TaxEngine.ProvisionalTaxResult;

/** Resolves exact economic adjustments before one final tax pass; does not authorize promotions or payments. */
public final class TaxAdjustmentResolver {
    /** Full admitted owner plan in approved order; unknown classifications and inexact targets fail closed. */
    public AdjustedLines resolve(ProvisionalTaxResult provisional, List<Adjustment> adjustments,
            FundingPlan funding, TaxPolicy policy) {
        Objects.requireNonNull(provisional);
        TaxEngine.checkPolicy(policy, provisional.context());
        if (!provisional.policy().equals(policy)) {
            throw TaxEngine.failure("TAX_SNAPSHOT_POLICY_MISMATCH");
        }
        Objects.requireNonNull(funding);
        var zero = new MoneyMinor(policy.currency(), 0);
        if (!funding.storedPlan().currency().equals(policy.currency())) {
            throw TaxEngine.failure("TAX_CURRENCY_MISMATCH");
        }
        adjustments = PricingRuleCandidate.bounded(adjustments, 256);
        var lines = new LinkedHashMap<String, TaxLineResult>();
        provisional.lines().forEach(line -> lines.put(line.invoiceLineRef(), line));
        var consideration = new HashMap<String, MoneyMinor>();
        var seen = new HashSet<AdjustmentKey>();
        var assetKinds = new HashMap<AssetRef, BenefitKind>();
        var traces = new ArrayList<Trace>();
        int previousStage = -1;
        for (var adjustment : adjustments) {
            var evidence = adjustment.evidence();
            if (!evidence.policyNo().equals(policy.policyNo()) || evidence.policyVersion() != policy.version()) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_POLICY_MISMATCH");
            }
            if (!adjustment.amount().currency().equals(policy.currency())) {
                throw TaxEngine.failure("TAX_CURRENCY_MISMATCH");
            }
            if (evidence.treatment() == TaxTreatment.UNKNOWN) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_CLASS_UNKNOWN");
            }
            int stage = stage(evidence.kind());
            if (stage < previousStage) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_ORDER_INVALID");
            }
            previousStage = stage;
            var priorKind = assetKinds.putIfAbsent(evidence.asset(), evidence.kind());
            if (priorKind != null && priorKind != evidence.kind()) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_ASSET_CONFLICT");
            }
            if (!seen.add(new AdjustmentKey(evidence.asset(), evidence.kind(), adjustment.invoiceLineRef(),
                    evidence.funderRef()))) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_DUPLICATE");
            }
            var before = lines.get(adjustment.invoiceLineRef());
            if (before == null) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_LINE_MISSING");
            }
            var after = before;
            if (evidence.treatment() == TaxTreatment.SETTLES_CONSIDERATION) {
                if (evidence.amountBasis() != AmountBasis.PAYABLE_GROSS
                        || evidence.kind() != BenefitKind.POINTS && evidence.kind() != BenefitKind.CASH_COUPON
                        && evidence.kind() != BenefitKind.DEDUCTION_COUPON && evidence.kind() != BenefitKind.ACTIVITY
                        && evidence.kind() != BenefitKind.PERCENT_COUPON) {
                    throw TaxEngine.failure("TAX_ADJUSTMENT_BASIS_UNSUPPORTED");
                }
                consideration.merge(before.invoiceLineRef(), adjustment.amount(), MoneyMinor::add);
            } else {
                after = reduce(before, adjustment.amount(), evidence.amountBasis());
                lines.put(after.invoiceLineRef(), after);
            }
            if (consideration.getOrDefault(after.invoiceLineRef(), zero).minor() > after.gross().minor()) {
                throw TaxEngine.failure("TAX_CONSIDERATION_EXCEEDS_LINE");
            }
            traces.add(new Trace(adjustment, before, after, after.net().subtract(before.net()),
                    after.tax().subtract(before.tax()), after.gross().subtract(before.gross())));
        }
        var offsets = zero;
        for (var value : consideration.values()) {
            offsets = offsets.add(value).requireJsonSafe();
        }
        var output = List.copyOf(lines.values());
        var due = TaxEngine.sum(output, policy.currency(), 2).subtract(offsets).requireNonNegative();
        if (due.minor() < funding.storedPlan().add(funding.otherPaymentPlans()).minor()) {
            throw TaxEngine.failure("TAX_FUNDING_EXCEEDS_DUE");
        }
        return new AdjustedLines(provisional, output, traces, funding, offsets);
    }

    private static TaxLineResult reduce(TaxLineResult line, MoneyMinor amount, AmountBasis basis) {
        if (line.priceMode() == PriceTaxMode.INCLUSIVE) {
            if (basis != AmountBasis.PAYABLE_GROSS) {
                throw TaxEngine.failure("TAX_ADJUSTMENT_BASIS_UNSUPPORTED");
            }
            return TaxEngine.reprice(line, subtract(line.price(), amount));
        }
        if (basis == AmountBasis.TAX_EXCLUSIVE) {
            return TaxEngine.reprice(line, subtract(line.price(), amount));
        }
        long target = subtract(line.gross(), amount).minor();
        // gross(net) is strictly increasing at nonnegative VAT rates. At most 54 exact integer checks.
        long low = 0;
        long high = line.net().minor();
        while (low <= high) {
            long middle = low + (high - low) / 2;
            var candidate = TaxEngine.reprice(line, new MoneyMinor(line.price().currency(), middle));
            long gross = candidate.gross().minor();
            if (gross == target) {
                return candidate;
            }
            if (gross < target) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        throw TaxEngine.failure("TAX_ADJUSTMENT_UNREPRESENTABLE");
    }

    private static MoneyMinor subtract(MoneyMinor price, MoneyMinor amount) {
        if (amount.minor() > price.minor()) {
            throw TaxEngine.failure("TAX_ADJUSTMENT_EXCEEDS_LINE");
        }
        return price.subtract(amount);
    }

    private static int stage(BenefitKind kind) {
        return switch (kind) {
            case ACTIVITY -> 0;
            case CASH_COUPON, DEDUCTION_COUPON -> 1;
            case PERCENT_COUPON -> 2;
            case MANUAL_PERCENT, MANUAL_AMOUNT -> 3;
            case POINTS -> 5;
            case MANUAL_WAIVER -> 6;
            case STORED_VALUE, CASH_ROUNDING -> throw TaxEngine.failure("TAX_ADJUSTMENT_KIND_UNSUPPORTED");
        };
    }

    private record AdjustmentKey(AssetRef asset, BenefitKind kind, String line, String funder) {
    }

    public enum AmountBasis { TAX_EXCLUSIVE, PAYABLE_GROSS }

    /** Regional classification binds an original owner/funder and profile version; not an authorization token. */
    public record Evidence(AssetRef asset, BenefitKind kind, TaxTreatment treatment, AmountBasis amountBasis,
            String funderRef, String evidenceRef, String policyNo, int policyVersion) {
        public Evidence {
            Objects.requireNonNull(asset);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(treatment);
            Objects.requireNonNull(amountBasis);
            PricingPortion.requireIdentity(funderRef, 64);
            PricingPortion.requireIdentity(evidenceRef, 64);
            PricingPortion.requireIdentity(policyNo, 64);
            if (policyVersion < 1) {
                throw new IllegalArgumentException("Positive classification policy version required");
            }
        }
    }

    /** Amount is owner-approved actual economics on a frozen invoice line, never a rate to optimize again. */
    public record Adjustment(Evidence evidence, String invoiceLineRef, MoneyMinor amount) {
        public Adjustment {
            Objects.requireNonNull(evidence);
            PricingPortion.requireIdentity(invoiceLineRef, 64);
            Objects.requireNonNull(amount).requireNonNegative().requireJsonSafe();
        }
    }

    /** Stored value is a payment plan, separate from third-party consideration offsets. */
    public record FundingPlan(MoneyMinor storedPlan, MoneyMinor otherPaymentPlans) {
        public FundingPlan {
            Objects.requireNonNull(storedPlan).requireNonNegative().requireJsonSafe();
            Objects.requireNonNull(otherPaymentPlans).requireNonNegative().requireJsonSafe();
            storedPlan.add(otherPaymentPlans).requireJsonSafe();
        }
    }

    /** Signed tax effects preserve exact before/after evidence without fabricating a tax allocation. */
    public record Trace(Adjustment adjustment, TaxLineResult before, TaxLineResult after,
            MoneyMinor netEffect, MoneyMinor taxEffect, MoneyMinor grossEffect) {
    }

    /** Only this resolver can produce the complete immutable adjusted plan for finalization. */
    public static final class AdjustedLines {
        private final ProvisionalTaxResult provisional;
        private final List<TaxLineResult> lines;
        private final List<Trace> traces;
        private final FundingPlan funding;
        private final MoneyMinor considerationOffsets;

        private AdjustedLines(ProvisionalTaxResult provisional, List<TaxLineResult> lines,
                List<Trace> traces, FundingPlan funding, MoneyMinor considerationOffsets) {
            this.provisional = provisional;
            this.lines = List.copyOf(lines);
            this.traces = List.copyOf(traces);
            this.funding = funding;
            this.considerationOffsets = considerationOffsets;
        }

        public ProvisionalTaxResult provisional() { return provisional; }
        public List<TaxLineResult> lines() { return lines; }
        public List<Trace> traces() { return traces; }
        public FundingPlan funding() { return funding; }
        public MoneyMinor considerationOffsets() { return considerationOffsets; }
    }
}
