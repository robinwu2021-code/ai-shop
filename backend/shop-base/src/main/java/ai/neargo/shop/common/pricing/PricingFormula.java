package ai.neargo.shop.common.pricing;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.ApprovalEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Closed arithmetic contract; callers use PricingEngine for business checks, never formula output as authorization. */
public interface PricingFormula {
    PricingResult compute(Input input);

    record Input(PricingRuleFacts facts, EffectivePricingConfig config, List<Step> steps,
            List<ApprovalEvidence> approvals) {
        public Input {
            Objects.requireNonNull(facts);
            Objects.requireNonNull(config);
            steps = PricingRuleCandidate.bounded(steps, 128);
            approvals = PricingRuleCandidate.bounded(approvals, 64);
        }
    }

    enum Algorithm { SHARED_COUPON_V1, OWNER_FROZEN_V1 }

    sealed interface Operation permits Amount, Percent, OwnerAllocated {
    }

    record Amount(long amountMinor, long minBaseMinor) implements Operation {
        public Amount {
            nonNegative(amountMinor);
            nonNegative(minBaseMinor);
        }
    }

    /** cap=null or zero preserves the existing owner's uncapped convention; rate means payable basis points. */
    record Percent(int payableRateBp, Long capMinor, long minBaseMinor) implements Operation {
        public Percent {
            if (payableRateBp < 0 || payableRateBp > 10000) {
                throw new IllegalArgumentException("Payable rate outside basis-point range");
            }
            if (capMinor != null) {
                nonNegative(capMinor);
            }
            nonNegative(minBaseMinor);
        }
    }

    record OwnerAllocated() implements Operation {
    }

    record StepKey(AssetRef asset, BenefitKind kind) {
        public StepKey {
            Objects.requireNonNull(asset);
            Objects.requireNonNull(kind);
        }
    }

    record Step(BenefitKind kind, Operation operation, Quote quote) {
        public Step {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(quote);
        }

        public AssetRef asset() {
            return quote.owner().asset();
        }
    }

    /** Admitted original-owner evidence, not a signature, balance grant or a client-set approval. */
    record Quote(OwnerEvidence owner, String quoteRef, String basisHash, Algorithm algorithm,
            List<PortionAmount> bases, List<FundedAllocation> allocations) {
        public Quote {
            Objects.requireNonNull(owner);
            PricingPortion.requireIdentity(quoteRef, 64);
            if (basisHash == null || !basisHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Canonical owner basis digest required");
            }
            Objects.requireNonNull(algorithm);
            bases = PricingRuleCandidate.bounded(bases, 64);
            allocations = PricingRuleCandidate.bounded(allocations, 64);
            if (bases.isEmpty() || bases.size() != allocations.size()) {
                throw new IllegalArgumentException("Owner bases and allocations must align");
            }
            for (int i = 0; i < bases.size(); i++) {
                if (!bases.get(i).portion().equals(allocations.get(i).portion())) {
                    throw new IllegalArgumentException("Owner allocation order must match scoped bases");
                }
            }
        }
    }

    record FundingShare(String funderRef, MoneyMinor amount) {
        public FundingShare {
            PricingPortion.requireIdentity(funderRef, 64);
            Objects.requireNonNull(amount).requireNonNegative().requireJsonSafe();
        }
    }

    /** Funder allocations are supplied by their authority and preserved, never inferred from a default merchant. */
    record FundedAllocation(PricingPortion portion, MoneyMinor amount, List<FundingShare> funders) {
        public FundedAllocation {
            Objects.requireNonNull(portion);
            Objects.requireNonNull(amount).requireNonNegative().requireJsonSafe();
            funders = PricingRuleCandidate.bounded(funders, 16);
            var identities = new HashSet<String>();
            MoneyMinor total = new MoneyMinor(amount.currency(), 0);
            for (var share : funders) {
                if (!identities.add(share.funderRef())) {
                    throw new IllegalArgumentException("Duplicate source funder");
                }
                total = total.add(share.amount());
            }
            if (!total.equals(amount)) {
                throw new IllegalArgumentException("Funders must conserve this allocation's amount and currency");
            }
        }
    }

    /** Binds source/config/context, execution order, operation and scoped bases; it is not authenticity. */
    static String basisHash(PricingRuleFacts facts, EffectivePricingConfig config, List<StepKey> order,
            int position, Operation operation, Algorithm algorithm, List<PortionAmount> bases) {
        Object terms = switch (operation) {
            case Amount amount -> List.of("AMOUNT", amount.amountMinor(), amount.minBaseMinor());
            case Percent percent -> List.of("PERCENT", percent.payableRateBp(),
                    Objects.toString(percent.capMinor(), "NONE"), percent.minBaseMinor());
            case OwnerAllocated ignored -> List.of("OWNER_ALLOCATED");
        };
        var source = facts.prices();
        var access = facts.context();
        var identity = List.of("PRICING_BASIS_V1", source, facts.unitPolicies(), facts.appliedActivities(),
                facts.previousCoupons(), List.of(access.actorNo(), access.storeNo(), access.scopeNo(),
                        access.calculationBasis(), access.evaluatedAt().toString()), config.configHash(),
                order, position, terms, algorithm.name(), bases);
        return PricingJson.hash(PricingJson.canonical(PricingJson.JSON.valueToTree(identity)));
    }

    private static void nonNegative(long minor) {
        if (minor < 0 || minor > MoneyMinor.MAX_JSON_SAFE_MINOR) {
            throw new IllegalArgumentException("Amount outside nonnegative JSON-safe minor range");
        }
    }

    /** Internal failure boundary; unavailable owner evidence must not be ranked as a definitive ineligibility. */
    final class Failure extends IllegalArgumentException {
        final PricingResult.Status status;
        final String code;

        Failure(PricingResult.Status status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }
}
