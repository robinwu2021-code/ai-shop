package ai.neargo.shop.common.pricing;

import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Exact source-price eligibility partition; never an asset qualification or a payment balance. */
public final class PricingBenefitBasis {
    private PricingBenefitBasis() { }
    public record Entry(PricingUnit source, UnitPolicy policy) { }
    public record Snapshot(int schemaVersion, String currency, String sourceSnapshotHash, long sourceVersion,
            long priceTotalMinor, long eligibleMinor, long excludedMinor, List<Entry> entries, String snapshotHash) {
        public Snapshot { entries = List.copyOf(entries); }
    }

    /** Preserves source rounding and component quantity intervals; component quantities are never summed. */
    public static Snapshot freeze(PricingFacts facts, List<UnitPolicy> policies) {
        Objects.requireNonNull(facts);
        require(facts.units().size() <= 1024 && policies != null && policies.size() == facts.units().size());
        var byPortion = new HashMap<PricingPortion, UnitPolicy>();
        for (var policy : policies) {
            require(policy != null && policy.status() == ScopeStatus.OPEN
                    && byPortion.put(policy.portion(), policy) == null);
        }
        var entries = new ArrayList<Entry>();
        var bundleRoles = new HashMap<String, PricingRole>();
        long eligible = 0;
        long excluded = 0;
        for (var unit : facts.units()) {
            var policy = byPortion.remove(PricingPortion.from(unit));
            require(policy != null);
            if (policy.bundleGroup() != null && unit.baseAmount().minor() > 0) {
                var prior = bundleRoles.putIfAbsent(policy.bundleGroup(), policy.role());
                require(prior == null || prior == policy.role());
            }
            entries.add(new Entry(unit, policy));
            if (policy.promotionEligible()) eligible = Math.addExact(eligible, unit.baseAmount().minor());
            else excluded = Math.addExact(excluded, unit.baseAmount().minor());
        }
        require(byPortion.isEmpty());
        long total = facts.baseTotal().minor();
        require(Math.addExact(eligible, excluded) == total);
        var draft = new Snapshot(1, facts.currency(), facts.sourceSnapshotHash(), facts.sourceVersion(),
                total, eligible, excluded, entries, null);
        return new Snapshot(1, draft.currency(), draft.sourceSnapshotHash(), draft.sourceVersion(),
                total, eligible, excluded, entries, PricingSnapshotHash.of(draft));
    }

    /** A matching hash provides integrity only. The source and product owners must still admit these facts. */
    public static Snapshot validate(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        require(snapshot.schemaVersion() == 1 && !snapshot.entries().isEmpty() && snapshot.entries().size() <= 1024);
        var facts = new PricingFacts(snapshot.currency(), snapshot.sourceSnapshotHash(), snapshot.sourceVersion(),
                snapshot.entries().stream().map(Entry::source).toList());
        var expected = freeze(facts, snapshot.entries().stream().map(Entry::policy).toList());
        require(expected.equals(snapshot));
        return snapshot;
    }

    /** Explicit eligible priced portions only; no threshold quantity or legacy integer UseItem is fabricated. */
    public static List<PortionAmount> eligiblePortions(Snapshot snapshot) {
        validate(snapshot);
        return snapshot.entries().stream().filter(e -> e.policy().promotionEligible())
                .filter(e -> e.source().baseAmount().minor() > 0)
                .map(e -> new PortionAmount(PricingPortion.from(e.source()), e.source().baseAmount())).toList();
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Complete explicit baseline benefit basis required");
    }
}
