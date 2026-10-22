package ai.neargo.shop.common.pricing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Explicit pre-tax proposal and backend-verified owner evidence, never a client authorization request. */
public record PricingRuleCandidate(List<Benefit> benefits, List<OwnerEvidence> owners,
        List<ApprovalEvidence> approvals, MoneyMinor remainingPayable) {
    public PricingRuleCandidate {
        benefits = bounded(benefits, 64);
        owners = bounded(owners, 128);
        approvals = bounded(approvals, 64);
        Objects.requireNonNull(remainingPayable).requireNonNegative().requireJsonSafe();
    }

    public record AssetRef(String owner, String assetNo) {
        public AssetRef {
            PricingPortion.requireIdentity(owner, 32);
            PricingPortion.requireIdentity(assetNo, 64);
        }
    }

    public record Benefit(AssetRef asset, BenefitKind kind, MoneyMinor amount,
            List<PortionAmount> allocations, Integer payableRateBp) {
        public Benefit {
            Objects.requireNonNull(asset);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(amount).requireNonNegative().requireJsonSafe();
            allocations = bounded(allocations, 64);
            MoneyMinor allocated = new MoneyMinor(amount.currency(), 0);
            for (var allocation : allocations) {
                allocated = allocated.add(allocation.amount());
            }
            if (!allocated.equals(amount)) {
                throw new IllegalArgumentException("Benefit allocations must conserve its amount and currency");
            }
            if (allocations.isEmpty() || payableRateBp != null && (payableRateBp < 0 || payableRateBp > 10000)) {
                throw new IllegalArgumentException("Scoped benefit and valid optional rate required");
            }
        }

        public List<PricingPortion> portions() {
            return allocations.stream().map(PortionAmount::portion).toList();
        }
    }

    /** Amount allocation must refer to a complete source-priced portion, never an inferred prorated amount. */
    public record PortionAmount(PricingPortion portion, MoneyMinor amount) {
        public PortionAmount {
            Objects.requireNonNull(portion);
            Objects.requireNonNull(amount).requireNonNegative().requireJsonSafe();
        }
    }

    /** Owner confirms exact formula/basis/order and eligible unfunded remainder for this asset/context. */
    public record OwnerEvidence(AssetRef asset, Set<BenefitKind> combinableKinds,
            String formulaId, int formulaVersion, String calculationBasis, MoneyMinor eligibleRemainder) {
        public OwnerEvidence {
            Objects.requireNonNull(asset);
            combinableKinds = Set.copyOf(combinableKinds);
            PricingPortion.requireIdentity(formulaId, 64);
            PricingPortion.requireIdentity(calculationBasis, 32);
            if (formulaVersion < 1) {
                throw new IllegalArgumentException("Positive formula version required");
            }
            Objects.requireNonNull(eligibleRemainder).requireNonNegative().requireJsonSafe();
        }
    }

    /** Admission of approval provenance is the application owner's responsibility; fields bind its exact use. */
    public record ApprovalEvidence(AssetRef asset, BenefitKind kind, String actorNo, String storeNo,
            String scopeNo, MoneyMinor amount, String reasonCode, Instant expiresAt, String evidenceRef) {
        public ApprovalEvidence {
            Objects.requireNonNull(asset);
            Objects.requireNonNull(kind);
            PricingPortion.requireIdentity(actorNo, 32);
            PricingPortion.requireIdentity(storeNo, 32);
            PricingPortion.requireIdentity(scopeNo, 64);
            PricingPortion.requireIdentity(reasonCode, 32);
            PricingPortion.requireIdentity(evidenceRef, 64);
            Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(amount).requireNonNegative().requireJsonSafe();
        }
    }

    static <T> List<T> bounded(List<T> values, int maximum) {
        if (values == null || values.size() > maximum) {
            throw new IllegalArgumentException("Rule input exceeds bounded collection size");
        }
        return List.copyOf(values);
    }
}
