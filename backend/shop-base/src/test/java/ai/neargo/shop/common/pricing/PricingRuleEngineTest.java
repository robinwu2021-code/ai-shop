package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.ACTIVITY;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.PERCENT_COUPON;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.CASH_COUPON;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.STORED_VALUE;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.POINTS;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.MANUAL_PERCENT;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.MANUAL_AMOUNT;
import static ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind.MANUAL_WAIVER;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.ApprovalEvidence;
import ai.neargo.shop.common.pricing.PricingRuleFacts.AccessContext;
import ai.neargo.shop.common.pricing.PricingRuleFacts.CouponUse;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;

class PricingRuleEngineTest {
    private final PricingRuleEngine engine = new PricingRuleEngine();
    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final PricingPortion ALL = new PricingPortion("LINE", null, BigDecimal.ZERO, BigDecimal.TWO);

    @Test
    void denyCannotBeOverriddenByAllow() throws Exception {
        var selected = List.of(benefit("P", PERCENT_COUPON, 1000), benefit("C", CASH_COUPON, 1000),
                benefit("S", STORED_VALUE, 1000));
        var issues = engine.validate(facts(List.of(benefit("A", ACTIVITY, 1000))), config(true),
                candidate(selected, 6000));
        assertThat(issues).extracting(RuleViolation::ruleNo).contains("B01", "B02");
        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.ruleVersion()).isEqualTo(1);
            assertThat(issue.stage()).isNotNull();
            assertThat(issue.code()).isNotBlank();
            assertThat(issue.sourceLineNos()).contains("LINE");
        });
    }

    @Test
    void configuredActivityPolicyIsNotEvidenceThatAnActivityWasApplied() throws Exception {
        var selected = List.of(benefit("C", CASH_COUPON, 1000));
        assertThat(engine.validate(facts(List.of()), config(false), candidate(selected, 9000))).isEmpty();
    }

    @Test
    void couponRejectsManualWaiverAndManualPercentRejectsPoints() throws Exception {
        assertThat(codes(List.of(benefit("C", CASH_COUPON, 1000), benefit("W", MANUAL_WAIVER, 100)), 8900))
                .contains("COUPON_MANUAL_CONFLICT");
        assertThat(codes(List.of(benefit("M", MANUAL_PERCENT, 1000), benefit("I", POINTS, 100)), 8900))
                .contains("MANUAL_BENEFIT_CONFLICT");
    }

    @Test
    void countsUniqueAssetsAcrossRootHistoryAndChecksEachLimit() throws Exception {
        var current = benefit("C", CASH_COUPON, 1000);
        var prior = List.of(new CouponUse(new AssetRef("OWNER", "OLD1"), CASH_COUPON),
                new CouponUse(new AssetRef("OWNER", "OLD2"), CASH_COUPON));
        var f = facts(List.of());
        f = new PricingRuleFacts(f.prices(), f.context(), f.unitPolicies(), f.appliedActivities(), prior);
        assertThat(engine.validate(f, config(false), candidate(List.of(current), 9000)))
                .extracting(RuleViolation::ruleNo).contains("B05");
        f = new PricingRuleFacts(f.prices(), f.context(), f.unitPolicies(), List.of(),
                List.of(new CouponUse(current.asset(), CASH_COUPON)));
        assertThat(engine.validate(f, config(false), candidate(List.of(current), 9000))).isEmpty();
    }

    @Test
    void disjointAllowsAdjacentRangesButRejectsOverlappingPercentCoupons() throws Exception {
        var left = new PricingPortion("LINE", null, BigDecimal.ZERO, BigDecimal.ONE);
        var right = new PricingPortion("LINE", null, BigDecimal.ONE, BigDecimal.TWO);
        var a = new Benefit(new AssetRef("OWNER", "P1"), PERCENT_COUPON, money(1000),
                List.of(new PortionAmount(left, money(1000))), null);
        var b = new Benefit(new AssetRef("OWNER", "P2"), PERCENT_COUPON, money(1000),
                List.of(new PortionAmount(right, money(1000))), null);
        var c = config(false, "{\"couponLimits\":{\"total\":3,\"percent\":2},"
                + "\"discountCouponPolicy\":{\"maxUsedPerOrder\":2}}");
        assertThat(engine.validate(splitFacts(), c, candidate(List.of(a, b), 8000))).isEmpty();
        assertThat(engine.validate(facts(List.of()), c, candidate(List.of(a, a("P2", 1000)), 8000)))
                .extracting(RuleViolation::ruleNo).contains("B06");
    }

    @Test
    void ownerMustConfirmCombinationBasisAndOrderEvenWhenStoreAllowsIt() throws Exception {
        var benefits = List.of(benefit("P", PERCENT_COUPON, 1000), benefit("C", CASH_COUPON, 1000));
        var c = candidate(benefits, 8000);
        c = new PricingRuleCandidate(c.benefits(), List.of(), List.of(), c.remainingPayable());
        assertThat(engine.validate(facts(List.of()), config(false), c))
                .extracting(RuleViolation::ruleNo).contains("B03");
        var wrong = new OwnerEvidence(benefits.get(0).asset(), Set.of(CASH_COUPON), "OTHER", 1,
                "PAYABLE_GROSS", money(10000));
        c = new PricingRuleCandidate(benefits, List.of(wrong), List.of(), money(8000));
        assertThat(engine.validate(facts(List.of()), config(false), c))
                .extracting(RuleViolation::code).contains("OWNER_BASIS_OR_COMBINATION_UNVERIFIED");
    }

    @Test
    void storedValueCombinationNeedsBothOwnersPermission() throws Exception {
        var benefits = List.of(benefit("C", CASH_COUPON, 1000), benefit("S", STORED_VALUE, 1000));
        var c = candidate(benefits, 8000);
        var owners = new ArrayList<>(c.owners());
        var e = owners.get(0);
        owners.set(0, new OwnerEvidence(e.asset(), Set.of(), e.formulaId(), 1, "PAYABLE_GROSS", money(10000)));
        c = new PricingRuleCandidate(benefits, owners, List.of(), money(8000));
        assertThat(engine.validate(facts(List.of()), config(true), c))
                .extracting(RuleViolation::ruleNo).contains("B09");
    }

    @Test
    void pointsMustFollowStoredPlanAndCannotExceedEligibleRemainder() throws Exception {
        var benefits = List.of(benefit("I", POINTS, 100), benefit("S", STORED_VALUE, 1000));
        assertThat(engine.validate(facts(List.of()), config(true), candidate(benefits, 8900)))
                .extracting(RuleViolation::code).contains("INVALID_STAGE_ORDER");
        var c = candidate(List.of(benefit("I", POINTS, 200)), 9800);
        var e = c.owners().get(0);
        c = new PricingRuleCandidate(c.benefits(), List.of(new OwnerEvidence(e.asset(), e.combinableKinds(),
                e.formulaId(), 1, "PAYABLE_GROSS", money(100))), List.of(), money(9800));
        assertThat(engine.validate(facts(List.of()), config(false), c))
                .extracting(RuleViolation::code).contains("OWNER_REMAINDER_EXCEEDED");
    }

    @Test
    void manualOperationRequiresMatchingUnexpiredApprovalAndConfiguredReason() throws Exception {
        var manual = benefit("M", MANUAL_AMOUNT, 1000);
        var c = candidate(List.of(manual), 9000);
        assertThat(engine.validate(facts(List.of()), config(false), c))
                .extracting(RuleViolation::ruleNo).contains("B11");
        var good = approval(manual, "STAFF", NOW.plusSeconds(1));
        c = new PricingRuleCandidate(c.benefits(), c.owners(), List.of(good), money(9000));
        assertThat(engine.validate(facts(List.of()), config(false), c)).isEmpty();
        for (var bad : List.of(approval(manual, "OTHER", NOW.plusSeconds(1)), approval(manual, "STAFF", NOW))) {
            c = new PricingRuleCandidate(c.benefits(), c.owners(), List.of(bad), money(9000));
            assertThat(engine.validate(facts(List.of()), config(false), c))
                    .extracting(RuleViolation::code).contains("MANUAL_APPROVAL_REQUIRED");
        }
    }

    @Test
    void manualPercentMustRespectStoreFloorEvenWithApproval() throws Exception {
        var manual = new Benefit(new AssetRef("OWNER", "M"), MANUAL_PERCENT, money(1000),
                List.of(new PortionAmount(ALL, money(1000))), 7000);
        var c = candidate(List.of(manual), 9000);
        c = new PricingRuleCandidate(c.benefits(), c.owners(), List.of(approval(manual, "STAFF", NOW.plusSeconds(1))),
                money(9000));
        assertThat(engine.validate(facts(List.of()), config(false,
                "{\"manualOperations\":{\"minPayableRateBp\":8000}}"), c))
                .extracting(RuleViolation::code).contains("MANUAL_RATE_BELOW_FLOOR");
    }

    @Test
    void excludedAndPaidScopesCannotBeDiscountedAndAmountsMustConserve() throws Exception {
        var f = facts(List.of());
        for (var policy : List.of(new UnitPolicy(ALL, false, ScopeStatus.OPEN, null, PricingRole.NONE),
                new UnitPolicy(ALL, true, ScopeStatus.PAID, null, PricingRole.NONE),
                new UnitPolicy(ALL, true, ScopeStatus.UNKNOWN, null, PricingRole.NONE))) {
            var restricted = new PricingRuleFacts(f.prices(), f.context(), List.of(policy), List.of(), List.of());
            assertThat(engine.validate(restricted, config(false), candidate(List.of(a("P", 1000)), 9000)))
                    .extracting(RuleViolation::ruleNo).contains("B12");
        }
        assertThat(codes(List.of(a("P", 1000)), 9999)).contains("AMOUNT_NOT_CONSERVED");
    }

    @Test
    void unknownPortionsDuplicateAssetsAndCurrencyMismatchAreRejected() throws Exception {
        var outside = new Benefit(new AssetRef("OWNER", "X"), CASH_COUPON, money(100),
                List.of(new PortionAmount(new PricingPortion("OTHER", null, BigDecimal.ZERO, BigDecimal.ONE),
                        money(100))),
                null);
        assertThat(codes(List.of(outside), 9900)).contains("INVALID_BENEFIT_SCOPE");
        assertThat(codes(List.of(a("P", 100), a("P", 100)), 9800)).contains("DUPLICATE_ASSET");
        var foreign = new Benefit(new AssetRef("OWNER", "F"), CASH_COUPON, new MoneyMinor("USD", 100),
                List.of(new PortionAmount(ALL, new MoneyMinor("USD", 100))), null);
        assertThat(codes(List.of(foreign), 9900)).contains("CURRENCY_MISMATCH");
    }

    @Test
    void returnedConflictsAreImmutableAndDeterministic() throws Exception {
        var c = candidate(List.of(a("P", 1000), benefit("S", STORED_VALUE, 1000)), 8000);
        var first = engine.validate(facts(List.of()), config(true), c);
        assertThat(first).isEqualTo(engine.validate(facts(List.of()), config(true), c));
        assertThatThrownBy(first::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void discountsCannotConsumeExcludedItemsAmountThroughAnEligibleScope() throws Exception {
        var eligible = new PricingUnit("LINE", null, "G", "S", "R", "T", BigDecimal.ZERO,
                BigDecimal.TWO, money(2500), money(5000));
        var excluded = new PricingUnit("EXCLUDED", null, "G2", "S2", "R", "T2", BigDecimal.ZERO,
                BigDecimal.ONE, money(5000), money(5000));
        var f = facts(List.of());
        f = new PricingRuleFacts(new PricingFacts("CNY", "b".repeat(64), 1, List.of(eligible, excluded)), f.context(),
                List.of(new UnitPolicy(ALL, true, ScopeStatus.OPEN, null, PricingRole.NONE),
                        new UnitPolicy(PricingPortion.from(excluded), false, ScopeStatus.OPEN, null, PricingRole.NONE)),
                List.of(), List.of());
        assertThat(engine.validate(f, config(false), candidate(List.of(a("P", 7000)), 3000)))
                .extracting(RuleViolation::code).contains("EXCLUDED_AMOUNT_NOT_PRESERVED");
    }

    @Test
    void multipleAssetsOfSameKindRequireEachOwnerToAllowThatKind() throws Exception {
        var left = new Benefit(new AssetRef("OWNER", "P1"), PERCENT_COUPON, money(1000),
                List.of(new PortionAmount(new PricingPortion("LINE", null, BigDecimal.ZERO, BigDecimal.ONE),
                        money(1000))),
                null);
        var right = new Benefit(new AssetRef("OWNER", "P2"), PERCENT_COUPON, money(1000),
                List.of(new PortionAmount(new PricingPortion("LINE", null, BigDecimal.ONE, BigDecimal.TWO),
                        money(1000))),
                null);
        var proposal = candidate(List.of(left, right), 8000);
        var owners = proposal.owners().stream().map(e -> new OwnerEvidence(e.asset(), Set.of(), e.formulaId(),
                e.formulaVersion(), e.calculationBasis(), e.eligibleRemainder())).toList();
        proposal = new PricingRuleCandidate(proposal.benefits(), owners, List.of(), money(8000));
        assertThat(engine.validate(splitFacts(), config(false,
                "{\"couponLimits\":{\"total\":3,\"percent\":2},"
                        + "\"discountCouponPolicy\":{\"maxUsedPerOrder\":2}}"), proposal))
                .extracting(RuleViolation::code).contains("OWNER_BASIS_OR_COMBINATION_UNVERIFIED");
    }

    @Test
    void parentAndLeafCannotBothContributeToSourcePrice() throws Exception {
        var f = facts(List.of());
        var first = f.prices().units().get(0);
        var second = new PricingUnit("LEAF", "PART", "G2", "S2", "R", "T2", BigDecimal.ZERO,
                BigDecimal.ONE, money(1000), money(1000));
        f = new PricingRuleFacts(new PricingFacts("CNY", "a".repeat(64), 1, List.of(first, second)), f.context(),
                List.of(new UnitPolicy(ALL, true, ScopeStatus.OPEN, "BUNDLE1", PricingRole.PARENT),
                        new UnitPolicy(PricingPortion.from(second), true, ScopeStatus.OPEN, "BUNDLE1",
                                PricingRole.LEAF)),
                List.of(), List.of());
        assertThat(engine.validate(f, config(false), candidate(List.of(), 11000)))
                .extracting(RuleViolation::code).contains("BUNDLE_PARENT_LEAF_DOUBLE_COUNT");
    }

    @Test
    void stackedCouponsRespectMaximumConcurrentAssets() throws Exception {
        var benefits = List.of(a("P1", 100), a("P2", 100), a("P3", 100));
        var c = config(false, """
                {"couponLimits":{"total":4,"percent":3},
                 "discountCouponPolicy":{"maxUsedPerOrder":3,"overlapMode":"STACK",
                                         "maxUsedPerItem":2,"stackingEnabled":true}}
                """);
        assertThat(engine.validate(facts(List.of()), c, candidate(benefits, 9700)))
                .extracting(RuleViolation::code).contains("PERCENT_SCOPE_OVERLAP");
        assertThat(engine.validate(facts(List.of()), c, candidate(benefits.subList(0, 2), 9800))).isEmpty();
    }

    @Test
    void ownerAndApprovalEvidenceCannotBeDuplicatedToChooseOneConvenientValue() throws Exception {
        var benefit = a("P", 100);
        var c = candidate(List.of(benefit), 9900);
        c = new PricingRuleCandidate(c.benefits(), List.of(c.owners().get(0), c.owners().get(0)),
                List.of(), money(9900));
        assertThat(engine.validate(facts(List.of()), config(false), c))
                .extracting(RuleViolation::code).contains("DUPLICATE_EVIDENCE");
    }

    @Test
    void fabricatedCompiledResultCannotRemoveMandatoryRules() throws Exception {
        var good = config(false);
        var fake = new EffectivePricingConfig(good.definition(), List.of(), good.canonicalJson(), good.configHash());
        assertThat(engine.validate(facts(List.of()), fake, candidate(List.of(), 10000)))
                .extracting(RuleViolation::code).containsExactly("CONFIG_NOT_COMPILED");
    }

    @Test
    void staleApprovalAmountStoreScopeKindAndReasonAllFail() throws Exception {
        var b = benefit("M", MANUAL_AMOUNT, 1000);
        var good = approval(b, "STAFF", NOW.plusSeconds(10));
        var invalid = List.of(
                new ApprovalEvidence(b.asset(), b.kind(), "STAFF", "OTHER", "ROOT", b.amount(),
                        good.reasonCode(), good.expiresAt(), "A"),
                new ApprovalEvidence(b.asset(), b.kind(), "STAFF", "STORE", "OTHER", b.amount(),
                        good.reasonCode(), good.expiresAt(), "A"),
                new ApprovalEvidence(b.asset(), b.kind(), "STAFF", "STORE", "ROOT", money(999),
                        good.reasonCode(), good.expiresAt(), "A"),
                new ApprovalEvidence(b.asset(), MANUAL_PERCENT, "STAFF", "STORE", "ROOT", b.amount(),
                        good.reasonCode(), good.expiresAt(), "A"),
                new ApprovalEvidence(b.asset(), b.kind(), "STAFF", "STORE", "ROOT", b.amount(),
                        "UNREGISTERED", good.expiresAt(), "A"));
        for (var approval : invalid) {
            var c = candidate(List.of(b), 9000);
            c = new PricingRuleCandidate(c.benefits(), c.owners(), List.of(approval), money(9000));
            assertThat(engine.validate(facts(List.of()), config(false), c))
                    .extracting(RuleViolation::code).contains("MANUAL_APPROVAL_REQUIRED");
        }
    }

    @Test
    void repeatedDiscountsCannotSpendAnotherLinesUnrelatedValue() throws Exception {
        var f = twoLineFacts();
        var selected = List.of(benefit("C1", CASH_COUPON, 60), benefit("C2", CASH_COUPON, 60));
        var c = config(false, "{\"couponLimits\":{\"cash\":2,\"amountTotal\":2}}");
        assertThat(engine.validate(f, c, candidate(selected, 80)))
                .extracting(RuleViolation::code).contains("SCOPE_AMOUNT_EXCEEDED");
    }

    @Test
    void waiverCannotReduceAnAlreadyFundedPortion() throws Exception {
        var stored = benefit("S", STORED_VALUE, 100);
        var waiver = benefit("W", MANUAL_WAIVER, 100);
        var c = candidate(List.of(stored, waiver), 0);
        c = new PricingRuleCandidate(c.benefits(), c.owners(), List.of(approval(waiver, "STAFF", NOW.plusSeconds(1))),
                money(0));
        assertThat(engine.validate(twoLineFacts(), config(true), c))
                .extracting(RuleViolation::code).contains("SCOPE_AMOUNT_EXCEEDED");
        c = new PricingRuleCandidate(List.of(waiver), List.of(), c.approvals(), money(100));
        assertThat(engine.validate(twoLineFacts(), config(false), c))
                .extracting(RuleViolation::ruleNo).contains("B10");
    }

    @Test
    void manualBasisMustMatchTheVerifiedCalculationContext() throws Exception {
        var b = benefit("M", MANUAL_AMOUNT, 1000);
        var c = candidate(List.of(b), 9000);
        c = new PricingRuleCandidate(c.benefits(), c.owners(), List.of(approval(b, "STAFF", NOW.plusSeconds(1))),
                money(9000));
        assertThat(engine.validate(facts(List.of()), config(false,
                "{\"manualOperations\":{\"amountBasis\":\"TAX_EXCLUSIVE\"}}"), c))
                .extracting(RuleViolation::code).contains("MANUAL_BASIS_UNVERIFIED");
    }

    @Test
    void oneAssetAcrossMultiplePricedPortionsCountsOnceAndConservesEachAllocation() throws Exception {
        var f = splitFacts();
        var b = new Benefit(new AssetRef("OWNER", "C"), CASH_COUPON, money(1000),
                List.of(new PortionAmount(f.unitPolicies().get(0).portion(), money(300)),
                        new PortionAmount(f.unitPolicies().get(1).portion(), money(700))), null);
        assertThat(engine.validate(f, config(false), candidate(List.of(b), 9000))).isEmpty();
        assertThatThrownBy(() -> new Benefit(b.asset(), b.kind(), money(1000),
                List.of(new PortionAmount(ALL, money(999))), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialQuantityCannotInventProrationOfAnUnsplitSourceAmount() throws Exception {
        var p = new PricingPortion("LINE", null, BigDecimal.ZERO, BigDecimal.ONE);
        var b = new Benefit(new AssetRef("OWNER", "C"), CASH_COUPON, money(100),
                List.of(new PortionAmount(p, money(100))), null);
        assertThat(engine.validate(facts(List.of()), config(false), candidate(List.of(b), 9900)))
                .extracting(RuleViolation::code).contains("SCOPE_REQUIRES_PRICED_PORTION");
    }

    @Test
    void paidSourceEvidenceIdentifiesTheAffectedLineEvenWithoutAnyBenefits() throws Exception {
        var f = facts(List.of());
        f = new PricingRuleFacts(f.prices(), f.context(),
                List.of(new UnitPolicy(ALL, true, ScopeStatus.PAID, null, PricingRole.NONE)), List.of(), List.of());
        assertThat(engine.validate(f, config(false), candidate(List.of(), 10000)))
                .filteredOn(issue -> issue.code().equals("SOURCE_SCOPE_NOT_OPEN")).hasSize(1)
                .allSatisfy(issue -> assertThat(issue.sourceLineNos()).contains("LINE"));
    }

    @Test
    void fundingCanSettleExcludedGoodsWithoutDiscountingTheirInvoiceCharge() throws Exception {
        var f = facts(List.of());
        f = new PricingRuleFacts(f.prices(), f.context(),
                List.of(new UnitPolicy(ALL, false, ScopeStatus.OPEN, null, PricingRole.NONE)), List.of(), List.of());
        assertThat(engine.validate(f, config(true), candidate(List.of(benefit("S", STORED_VALUE, 10000)), 0)))
                .isEmpty();
    }

    private PricingRuleFacts splitFacts() {
        var f = facts(List.of());
        var units = List.of(
                new PricingUnit("LINE", null, "G", "SKU", "R1", "TAX1", BigDecimal.ZERO,
                        BigDecimal.ONE, money(5000), money(5000)),
                new PricingUnit("LINE", null, "G", "SKU", "R1", "TAX1", BigDecimal.ONE,
                        BigDecimal.TWO, money(5000), money(5000)));
        return new PricingRuleFacts(new PricingFacts("CNY", "a".repeat(64), 1, units), f.context(), units.stream()
                .map(unit -> new UnitPolicy(PricingPortion.from(unit), true, ScopeStatus.OPEN, null, PricingRole.NONE))
                .toList(), List.of(), List.of());
    }

    private PricingRuleFacts twoLineFacts() {
        var first = new PricingUnit("LINE", null, "G", "S", "R", "T", BigDecimal.ZERO,
                BigDecimal.TWO, money(50), money(100));
        var second = new PricingUnit("OTHER", null, "G2", "S2", "R", "T2", BigDecimal.ZERO,
                BigDecimal.ONE, money(100), money(100));
        return new PricingRuleFacts(new PricingFacts("CNY", "b".repeat(64), 1, List.of(first, second)),
                facts(List.of()).context(), List.of(new UnitPolicy(ALL, true, ScopeStatus.OPEN, null, PricingRole.NONE),
                        new UnitPolicy(PricingPortion.from(second), true, ScopeStatus.OPEN, null, PricingRole.NONE)),
                List.of(), List.of());
    }

    private List<String> codes(List<Benefit> benefits, long remaining) throws Exception {
        return engine.validate(facts(List.of()), config(true), candidate(benefits, remaining)).stream()
                .map(RuleViolation::code).toList();
    }

    private static Benefit a(String no, long amount) {
        return benefit(no, PERCENT_COUPON, amount);
    }
    private static Benefit benefit(String no, RuleDefinition.BenefitKind kind, long amount) {
        return new Benefit(new AssetRef("OWNER", no), kind, money(amount),
                List.of(new PortionAmount(ALL, money(amount))),
                kind == MANUAL_PERCENT ? 9000 : null);
    }
    private static MoneyMinor money(long amount) {
        return new MoneyMinor("CNY", amount);
    }
    private static PricingRuleFacts facts(List<Benefit> activities) {
        var unit = new PricingUnit("LINE", null, "G", "SKU", "R1", "TAX1", BigDecimal.ZERO,
                BigDecimal.TWO, money(5000), money(10000));
        return new PricingRuleFacts(new PricingFacts("CNY", "a".repeat(64), 1, List.of(unit)),
                new AccessContext("STAFF", "STORE", "ROOT", "PAYABLE_GROSS", NOW),
                List.of(new UnitPolicy(ALL, true, ScopeStatus.OPEN, null, PricingRole.NONE)), activities, List.of());
    }
    private static PricingRuleCandidate candidate(List<Benefit> benefits, long remaining) {
        var owners = benefits.stream().map(b -> new OwnerEvidence(b.asset(),
                Set.of(RuleDefinition.BenefitKind.values()),
                "DEDUCT_THEN_DISCOUNT_V1", 1, "PAYABLE_GROSS", money(10000))).distinct().toList();
        return new PricingRuleCandidate(benefits, owners, List.of(), money(remaining));
    }
    private static ApprovalEvidence approval(Benefit b, String actor, Instant expiry) {
        return new ApprovalEvidence(b.asset(), b.kind(), actor, "STORE", "ROOT", b.amount(), "SERVICE_RECOVERY",
                expiry, "APPROVAL1");
    }
    private EffectivePricingConfig config(boolean stored) throws Exception {
        return config(stored, "{}");
    }
    private EffectivePricingConfig config(boolean stored, String patch) throws Exception {
        var node = (ObjectNode) PricingConfigTestData.JSON.readTree(patch);
        node.put("storedValueEnabled", stored);
        node.withObject("manualOperations").putArray("reasonCategories").add("SERVICE_RECOVERY");
        var result = new PricingConfigCompiler().compile(PricingConfigTestData.template(), node.toString(),
                SystemConstraints.standard(), PricingConfigTestData.capabilities());
        assertThat(result.valid()).isTrue();
        return result.config();
    }
}
