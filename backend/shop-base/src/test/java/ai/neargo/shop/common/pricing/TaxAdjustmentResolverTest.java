package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.neargo.shop.common.pricing.TaxEngineTest.assertCode;
import static ai.neargo.shop.common.pricing.TaxTestData.adjustment;
import static ai.neargo.shop.common.pricing.TaxTestData.context;
import static ai.neargo.shop.common.pricing.TaxTestData.finish;
import static ai.neargo.shop.common.pricing.TaxTestData.funding;
import static ai.neargo.shop.common.pricing.TaxTestData.line;
import static ai.neargo.shop.common.pricing.TaxTestData.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.Adjustment;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.AmountBasis;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.Evidence;
import ai.neargo.shop.common.pricing.TaxEngine.ProvisionalTaxResult;

class TaxAdjustmentResolverTest {
    private final TaxEngine engine = new TaxEngine();
    private final TaxAdjustmentResolver resolver = new TaxAdjustmentResolver();

    @Test
    void pointsPriceReductionThenWaiverAreNotDeductedTwice() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var initial = estimate(p, 10500);
        var adjustments = List.of(adjustment(p, "P1", BenefitKind.POINTS, "L1", 1000,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS),
                adjustment(p, "W1", BenefitKind.MANUAL_WAIVER, "L1", 50,
                        TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS));
        var result = finish(initial, adjustments, funding(0, 0), p);
        assertThat(result.invoiceNet().minor()).isEqualTo(9000);
        assertThat(result.invoiceTax().minor()).isEqualTo(450);
        assertThat(result.invoiceGross().minor()).isEqualTo(9450);
        assertThat(result.customerDue().minor()).isEqualTo(9450);
        assertThat(result.considerationOffsets().minor()).isZero();
        assertThat(result.traces()).hasSize(2);
        assertThat(result.traces().getFirst().grossEffect().minor()).isEqualTo(-1000);
    }

    @Test
    void pointsConsiderationAndStoredPaymentDoNotReduceTaxOrDoubleCountStored() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var result = finish(estimate(p, 10500), List.of(adjustment(p, "P1", BenefitKind.POINTS,
                "L1", 1000, TaxTreatment.SETTLES_CONSIDERATION, AmountBasis.PAYABLE_GROSS)),
                funding(6000, 2000), p);
        assertThat(result.invoiceNet().minor()).isEqualTo(10000);
        assertThat(result.invoiceTax().minor()).isEqualTo(500);
        assertThat(result.invoiceGross().minor()).isEqualTo(10500);
        assertThat(result.considerationOffsets().minor()).isEqualTo(1000);
        assertThat(result.customerDue().minor()).isEqualTo(9500);
        assertThat(result.unassignedDue().minor()).isEqualTo(1500);
        assertThat(result.traces().getFirst().grossEffect().minor()).isZero();
    }

    @Test
    void exclusiveNetDiscountCanIncreaseGrossReductionAndCannotInvadeFixedStoredPlan() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var initial = estimate(p, 10000);
        var exact = List.of(adjustment(p, "W1", BenefitKind.MANUAL_WAIVER, "L1", 1000,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.TAX_EXCLUSIVE));
        var result = finish(initial, exact, funding(9000, 0), p);
        assertThat(result.invoiceGross().minor()).isEqualTo(9450);
        assertThat(result.traces().getFirst().grossEffect().minor()).isEqualTo(-1050);
        assertCode("TAX_FUNDING_EXCEEDS_DUE", () -> finish(initial, exact, funding(10000, 0), p));
        var offset = List.of(adjustment(p, "P1", BenefitKind.POINTS, "L1", 1000,
                TaxTreatment.SETTLES_CONSIDERATION, AmountBasis.PAYABLE_GROSS));
        assertCode("TAX_FUNDING_EXCEEDS_DUE", () -> finish(initial, offset, funding(10000, 0), p));
    }

    @Test
    void exactExclusiveGrossInverseRejectsAnUnrepresentableTarget() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var initial = estimate(p, 10); // net10, tax1, gross11; no integer net has gross10.
        assertCode("TAX_ADJUSTMENT_UNREPRESENTABLE", () -> finish(initial,
                List.of(adjustment(p, "W1", BenefitKind.MANUAL_WAIVER, "L1", 1,
                        TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        var result = finish(initial, List.of(adjustment(p, "W1", BenefitKind.MANUAL_WAIVER, "L1", 2,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS)), funding(0, 0), p);
        assertThat(result.invoiceNet().minor()).isEqualTo(9);
        assertThat(result.invoiceTax().minor()).isZero();
        assertThat(result.invoiceGross().minor()).isEqualTo(9);
    }

    @Test
    void unknownClassificationWrongProfileAndStoredAsDiscountAreBlocked() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var initial = estimate(p, 10500);
        assertCode("TAX_ADJUSTMENT_CLASS_UNKNOWN", () -> finish(initial, List.of(adjustment(p, "P1",
                BenefitKind.POINTS, "L1", 10, TaxTreatment.UNKNOWN, AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        var wrong = policy(PriceTaxMode.EXCLUSIVE);
        assertCode("TAX_ADJUSTMENT_POLICY_MISMATCH", () -> finish(initial, List.of(adjustment(wrong, "P1",
                BenefitKind.POINTS, "L1", 10, TaxTreatment.REDUCES_TAX_BASE,
                AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        assertCode("TAX_ADJUSTMENT_KIND_UNSUPPORTED", () -> finish(initial, List.of(adjustment(p, "S1",
                BenefitKind.STORED_VALUE, "L1", 10, TaxTreatment.SETTLES_CONSIDERATION,
                AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        assertCode("TAX_ADJUSTMENT_BASIS_UNSUPPORTED", () -> finish(initial, List.of(adjustment(p, "W1",
                BenefitKind.MANUAL_WAIVER, "L1", 10, TaxTreatment.REDUCES_TAX_BASE,
                AmountBasis.TAX_EXCLUSIVE)), funding(0, 0), p));
    }

    @Test
    void noForeignLineDuplicateFunderOrReorderedStageCanDisappearDuringResolution() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var initial = estimate(p, 10500);
        var points = adjustment(p, "P1", BenefitKind.POINTS, "L1", 10,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS);
        assertCode("TAX_ADJUSTMENT_LINE_MISSING", () -> finish(initial, List.of(adjustment(p, "P1",
                BenefitKind.POINTS, "FOREIGN", 10, TaxTreatment.REDUCES_TAX_BASE,
                AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        assertCode("TAX_ADJUSTMENT_DUPLICATE", () -> finish(initial, List.of(points, points), funding(0, 0), p));
        var waiver = adjustment(p, "W1", BenefitKind.MANUAL_WAIVER, "L1", 10,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS);
        assertCode("TAX_ADJUSTMENT_ORDER_INVALID", () -> finish(initial, List.of(waiver, points), funding(0, 0), p));
    }

    @Test
    void zeroPriceAndConsiderationOverdrawCannotExposeNegativeOrPartialPrices() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        assertCode("TAX_ADJUSTMENT_EXCEEDS_LINE", () -> finish(estimate(p, 0),
                List.of(adjustment(p, "P1", BenefitKind.POINTS, "L1", 1,
                        TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        assertCode("TAX_CONSIDERATION_EXCEEDS_LINE", () -> finish(estimate(p, 100), List.of(
                adjustment(p, "P1", BenefitKind.POINTS, "L1", 60,
                        TaxTreatment.SETTLES_CONSIDERATION, AmountBasis.PAYABLE_GROSS),
                adjustment(p, "P2", BenefitKind.POINTS, "L1", 60,
                        TaxTreatment.SETTLES_CONSIDERATION, AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
    }

    @Test
    void classificationKeepsOriginalFunderSharesAndFrozenEvidence() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var price = adjustment(p, "C1", BenefitKind.CASH_COUPON, "L1", 600,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS);
        var other = new Adjustment(new Evidence(price.evidence().asset(), BenefitKind.CASH_COUPON,
                TaxTreatment.SETTLES_CONSIDERATION, AmountBasis.PAYABLE_GROSS, "PLATFORM",
                "PLATFORM-CLEARING", p.policyNo(), p.version()), "L1", TaxTestData.money(400));
        var result = finish(estimate(p, 10500), List.of(price, other), funding(0, 0), p);
        assertThat(result.invoiceGross().minor()).isEqualTo(9900);
        assertThat(result.customerDue().minor()).isEqualTo(9500);
        assertThat(result.traces()).extracting(t -> t.adjustment().evidence().funderRef())
                .containsExactly("ENTITY", "PLATFORM");
        assertThatThrownBy(() -> result.traces().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void frozenPolicyCannotBeReplacedBetweenEstimateResolveAndFinalize() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var other = new TaxPolicy(p.policyNo(), 2, p.countryCode(), p.regionCode(), p.currency(),
                p.effectiveFrom(), p.effectiveTo(), p.priceMode(), p.allowedContexts(), p.rates(),
                p.algorithm(), "NEW-APPROVAL");
        var initial = estimate(p, 10500);
        assertCode("TAX_SNAPSHOT_POLICY_MISMATCH", () -> resolver.resolve(initial, List.of(), funding(0, 0), other));
        var rates = new LinkedHashMap<>(p.rates());
        rates.put(TaxClass.STANDARD, 600);
        var altered = new TaxPolicy(p.policyNo(), p.version(), p.countryCode(), p.regionCode(), p.currency(),
                p.effectiveFrom(), p.effectiveTo(), p.priceMode(), p.allowedContexts(), rates,
                p.algorithm(), p.evidenceRef());
        assertCode("TAX_SNAPSHOT_POLICY_MISMATCH", () -> resolver.resolve(initial, List.of(), funding(0, 0), altered));
        var adjusted = resolver.resolve(initial, List.of(), funding(0, 0), p);
        assertCode("TAX_SNAPSHOT_POLICY_MISMATCH", () -> engine.finalizeTax(adjusted, other));
        var first = engine.finalizeTax(adjusted, p);
        var repeated = engine.finalizeTax(adjusted, p);
        assertThat(first.lines()).isEqualTo(repeated.lines());
        assertThat(first.customerDue()).isEqualTo(repeated.customerDue());
        assertThat(initial.invoiceGross().minor()).isEqualTo(10500);
    }

    @Test
    void allEightOriginalSeedVectorsExecuteAgainstTheSameTaxImplementation() throws Exception {
        var seed = PricingConfigTestData.seed();
        assertThat(seed.path("calculationVectors").size()).isEqualTo(8);
        for (var vector : seed.path("calculationVectors")) {
            var mode = vector.path("profileNo").asText().contains("NET_PRICE")
                    ? PriceTaxMode.EXCLUSIVE : PriceTaxMode.INCLUSIVE;
            var profile = StreamSupport.stream(seed.path("regionalTaxProfiles").spliterator(), false)
                    .filter(item -> item.path("taxProfileNo").asText().equals(vector.path("profileNo").asText()))
                    .findFirst().orElseThrow();
            var p = TaxTestData.seedPolicy(profile);
            var legalContext = new TaxEngine.Context("AE", null, "AE", null, "TRN-TEST",
                    p.allowedContexts().iterator().next(), TaxTestData.POINT, TaxEngine.TaxPointStatus.CONFIRMED,
                    "a".repeat(64), 1);
            var lines = new ArrayList<TaxEngine.Line>();
            for (var source : vector.path("lines")) {
                lines.add(line(source.path("ref").asText(), source.path("priceMinor").asLong(),
                        TaxClass.valueOf(source.path("taxClass").asText()), mode));
            }
            var initial = engine.estimate(lines, p, legalContext);
            var adjustments = new ArrayList<Adjustment>();
            long remaining = initial.lines().getFirst().price().minor();
            int no = 0;
            for (var source : vector.path("adjustments")) {
                long amount = source.has("amountMinor") ? source.path("amountMinor").asLong()
                        : CouponCalculation.discount("PERCENT",
                                source.path("payableRateBp").asLong(), null, remaining);
                var treatment = TaxTreatment.valueOf(source.path("treatment").asText());
                var basis = source.has("amountBasis") ? AmountBasis.valueOf(source.path("amountBasis").asText())
                        : AmountBasis.TAX_EXCLUSIVE;
                adjustments.add(adjustment(p, "A" + no++, BenefitKind.valueOf(source.path("kind").asText()),
                        "L1", amount, treatment, basis));
                if (treatment == TaxTreatment.REDUCES_TAX_BASE) {
                    remaining -= amount;
                }
            }
            var stored = vector.path("funding").path("storedMinor").asLong(0);
            var cash = vector.path("funding").path("cashDueMinor").asLong(0);
            var result = finish(initial, adjustments, funding(stored, cash), p);
            var expected = vector.path("expected");
            assertThat(result.invoiceNet().minor()).as(vector.path("caseNo").asText())
                    .isEqualTo(expected.path("netMinor").asLong());
            assertThat(result.invoiceTax().minor()).isEqualTo(expected.path("taxMinor").asLong());
            assertThat(result.invoiceGross().minor()).isEqualTo(expected.path("invoiceGrossMinor").asLong());
            assertThat(result.customerDue().minor()).isEqualTo(expected.path("customerDueMinor").asLong());
            if (expected.has("cashChangeMinor")) {
                // CKO17 owns tender handling; this asserts the fixture's arithmetic without opening cash APIs.
                assertThat(vector.path("funding").path("cashTenderMinor").asLong() - cash)
                        .isEqualTo(expected.path("cashChangeMinor").asLong());
            }
            if (expected.has("pointsConsiderationMinor")) {
                assertThat(result.considerationOffsets().minor())
                        .isEqualTo(expected.path("pointsConsiderationMinor").asLong());
            }
        }
    }

    @Test
    void percentCouponCanSettleConsiderationOnlyWithExplicitOwnerAndRegionalClassification() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var result = assertDoesNotThrow(() -> finish(estimate(p, 10500), List.of(adjustment(p, "C1",
                BenefitKind.PERCENT_COUPON, "L1", 1050, TaxTreatment.SETTLES_CONSIDERATION,
                AmountBasis.PAYABLE_GROSS)), funding(0, 0), p));
        assertThat(result.invoiceTax().minor()).isEqualTo(500);
        assertThat(result.customerDue().minor()).isEqualTo(9450);
    }

    @Test
    void exactGrossInverseMatchesAllRepresentableSmallIntegerTargetsAndRejectsEveryGap() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        for (int price : List.of(10, 20, 99, 100, 101)) {
            var initial = estimate(p, price);
            long gross = initial.invoiceGross().minor();
            for (int target = 0; target <= gross; target++) {
                long desired = target;
                Long matchingNet = null;
                for (long net = 0; net <= price; net++) {
                    long tax = (net * 500 + 5000) / 10000;
                    if (net + tax == desired) {
                        matchingNet = net;
                        break;
                    }
                }
                var adjustments = List.of(adjustment(p, "W1", BenefitKind.MANUAL_WAIVER, "L1", gross - desired,
                        TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS));
                if (matchingNet == null) {
                    assertCode("TAX_ADJUSTMENT_UNREPRESENTABLE", () -> finish(initial, adjustments, funding(0, 0), p));
                } else {
                    var result = finish(initial, adjustments, funding(0, 0), p);
                    assertThat(result.invoiceGross().minor()).isEqualTo(desired);
                    assertThat(result.invoiceNet().minor()).isEqualTo(matchingNet);
                }
            }
        }
    }

    @Test
    void laterPriceReductionCannotConsumePreviouslyOffsetConsideration() {
        var p = policy(PriceTaxMode.INCLUSIVE);
        var initial = estimate(p, 100);
        var offset = adjustment(p, "C1", BenefitKind.CASH_COUPON, "L1", 80,
                TaxTreatment.SETTLES_CONSIDERATION, AmountBasis.PAYABLE_GROSS);
        var points = adjustment(p, "P1", BenefitKind.POINTS, "L1", 30,
                TaxTreatment.REDUCES_TAX_BASE, AmountBasis.PAYABLE_GROSS);
        assertCode("TAX_CONSIDERATION_EXCEEDS_LINE", () -> finish(initial, List.of(offset, points), funding(0, 0), p));
    }

    @Test
    void reportingKeepsZeroRatedExemptAndOutOfScopeDistinctWithOriginalPolicyAndTaxPoint() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var initial = engine.estimate(List.of(line("L1", 100, TaxClass.ZERO_RATED, PriceTaxMode.EXCLUSIVE),
                line("L2", 100, TaxClass.EXEMPT, PriceTaxMode.EXCLUSIVE),
                line("L3", 100, TaxClass.OUT_OF_SCOPE, PriceTaxMode.EXCLUSIVE)), p, context());
        var result = finish(initial, List.of(), funding(0, 0), p);
        assertThat(result.summaries()).extracting(TaxResult.Summary::taxClass)
                .containsExactly(TaxClass.ZERO_RATED, TaxClass.EXEMPT, TaxClass.OUT_OF_SCOPE);
        assertThat(result.summaries().getFirst().rateBp()).isZero();
        assertThat(result.summaries().get(1).rateBp()).isNull();
        assertThat(result.policy()).isEqualTo(p);
        assertThat(result.context().taxPoint()).isEqualTo(TaxTestData.POINT);
        assertThat(result.invoiceNet().minor()).isEqualTo(300);
        assertThat(result.invoiceTax().minor()).isZero();
    }

    @Test
    void aaAllocatesFrozenOriginalTaxRatherThanRoundingEachPersonsNetAgain() {
        var p = policy(PriceTaxMode.EXCLUSIVE);
        var frozen = finish(estimate(p, 20), List.of(), funding(0, 0), p);
        assertThat(frozen.invoiceTax().minor()).isEqualTo(1);
        var allocated = frozen.allocateInvoiceLine("L1", List.of(
                new TaxResult.Share("PERSON1", TaxTestData.money(10)),
                new TaxResult.Share("PERSON2", TaxTestData.money(11))));
        assertThat(allocated).hasSize(2);
        assertThat(allocated).extracting(a -> a.tax().minor()).containsExactly(0L, 1L);
        assertThat(allocated).extracting(a -> a.net().minor()).containsExactly(10L, 10L);
        assertThat(allocated.getFirst().invoiceLine()).isEqualTo(frozen.lines().getFirst());
        assertThat(allocated.getFirst().context().taxPoint()).isEqualTo(TaxTestData.POINT);
        assertCode("TAX_SHARE_TOTAL_MISMATCH", () -> frozen.allocateInvoiceLine("L1", List.of(
                new TaxResult.Share("PERSON1", TaxTestData.money(10)))));
        assertCode("TAX_SHARE_DUPLICATE", () -> frozen.allocateInvoiceLine("L1", List.of(
                new TaxResult.Share("PERSON1", TaxTestData.money(10)),
                new TaxResult.Share("PERSON1", TaxTestData.money(11)))));
    }

    private ProvisionalTaxResult estimate(TaxPolicy p, long amount) {
        return engine.estimate(List.of(line("L1", amount, TaxClass.STANDARD, p.priceMode())), p, context());
    }
}
