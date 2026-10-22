package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.common.pricing.PricingFormula.Algorithm;
import ai.neargo.shop.common.pricing.PricingFormula.FundedAllocation;
import ai.neargo.shop.common.pricing.PricingFormula.FundingShare;
import ai.neargo.shop.common.pricing.PricingFormula.Input;
import ai.neargo.shop.common.pricing.PricingFormula.Operation;
import ai.neargo.shop.common.pricing.PricingFormula.Quote;
import ai.neargo.shop.common.pricing.PricingFormula.Step;
import ai.neargo.shop.common.pricing.PricingFormula.StepKey;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.PricingRuleFacts.AccessContext;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

final class PricingEngineTestData {
    private PricingEngineTestData() {
    }

    record Spec(String no, BenefitKind kind, Operation operation, int[] lines, long[] shares,
            Algorithm algorithm, String funder) {
    }

    static Spec step(String no, BenefitKind kind, Operation operation, int line, long share) {
        return new Spec(no, kind, operation, new int[] {line}, new long[] {share},
                Algorithm.SHARED_COUPON_V1, "ENTITY");
    }

    static Input input(long[] amounts, String patch, Spec... specs) throws Exception {
        return inputWithFormula("DEDUCT_THEN_DISCOUNT_V1", amounts, patch, specs);
    }

    static Input inputWithFormula(String formulaId, long[] amounts, String patch, Spec... specs) throws Exception {
        var units = new ArrayList<PricingUnit>();
        for (int i = 0; i < amounts.length; i++) {
            units.add(new PricingUnit("L" + i, null, "G", "SKU", "R1", "TAX" + i, BigDecimal.ZERO,
                    BigDecimal.ONE, money(amounts[i]), money(amounts[i])));
        }
        var defaults = new PricingConfigCompiler().compile(PricingConfigTestData.template(), """
                {"couponLimits":{"total":4,"percent":2,"amountTotal":2,"cash":2,"deduction":2},
                 "discountCouponPolicy":{"maxUsedPerOrder":2}}
                """, SystemConstraints.standard(), PricingConfigTestData.capabilities());
        var template = (ObjectNode)
                PricingConfigTestData.JSON.readTree(defaults.config().canonicalJson());
        template.put("formulaId", formulaId);
        var config = new PricingConfigCompiler().compile(template.toString(), patch,
                SystemConstraints.standard(), PricingConfigTestData.capabilities()).config();
        var activities = Arrays.stream(specs).filter(s -> s.kind() == BenefitKind.ACTIVITY).map(s ->
                new Benefit(asset(s.no()), s.kind(), money(Arrays.stream(s.shares()).sum()),
                        allocations(units, s), null)).toList();
        var facts = new PricingRuleFacts(new PricingFacts("CNY", "a".repeat(64), 1, units),
                new AccessContext("STAFF", "STORE", "ROOT", "PAYABLE_GROSS", Instant.parse("2026-10-22T08:00:00Z")),
                units.stream().map(u -> new UnitPolicy(PricingPortion.from(u), true, ScopeStatus.OPEN,
                        null, PricingRole.NONE)).toList(), activities, List.of());
        var order = Arrays.stream(specs).map(s -> new StepKey(asset(s.no()), s.kind())).toList();
        long[] remaining = amounts.clone();
        var steps = new ArrayList<Step>();
        for (int index = 0; index < specs.length; index++) {
            var spec = specs[index];
            var bases = new ArrayList<PortionAmount>();
            var allocations = new ArrayList<FundedAllocation>();
            for (int j = 0; j < spec.lines().length; j++) {
                int line = spec.lines()[j];
                var portion = PricingPortion.from(units.get(line));
                bases.add(new PortionAmount(portion, money(remaining[line])));
                long share = spec.shares()[j];
                allocations.add(new FundedAllocation(portion, money(share), share == 0 ? List.of()
                        : List.of(new FundingShare(spec.funder(), money(share)))));
                remaining[line] -= share;
            }
            var owner = new OwnerEvidence(asset(spec.no()), Set.of(BenefitKind.values()),
                    config.definition().formulaId(), 1, facts.context().calculationBasis(), money(100000000));
            var hash = PricingFormula.basisHash(facts, config, order, index, spec.operation(), spec.algorithm(), bases);
            steps.add(new Step(spec.kind(), spec.operation(), new Quote(owner, "Q" + spec.no(), hash,
                    spec.algorithm(), bases, allocations)));
        }
        return new Input(facts, config, steps, List.of());
    }

    private static List<PortionAmount> allocations(List<PricingUnit> units, Spec spec) {
        var allocations = new ArrayList<PortionAmount>();
        for (int i = 0; i < spec.lines().length; i++) {
            allocations.add(new PortionAmount(PricingPortion.from(units.get(spec.lines()[i])),
                    money(spec.shares()[i])));
        }
        return allocations;
    }

    static AssetRef asset(String no) {
        return new AssetRef("OWNER", no);
    }

    static MoneyMinor money(long amount) {
        return new MoneyMinor("CNY", amount);
    }
}
