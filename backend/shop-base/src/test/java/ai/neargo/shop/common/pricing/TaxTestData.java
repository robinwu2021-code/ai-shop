package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.common.pricing.TaxEngine.Context;
import ai.neargo.shop.common.pricing.TaxEngine.Line;
import ai.neargo.shop.common.pricing.TaxEngine.TaxPointStatus;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.Adjustment;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.AmountBasis;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.Evidence;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.FundingPlan;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

final class TaxTestData {
    static final Instant POINT = Instant.parse("2026-10-22T04:00:00Z");

    static MoneyMinor money(long value) {
        return new MoneyMinor("AED", value);
    }

    static TaxPolicy policy(PriceTaxMode mode) {
        var rates = new LinkedHashMap<TaxClass, Integer>();
        rates.put(TaxClass.STANDARD, 500);
        rates.put(TaxClass.ZERO_RATED, 0);
        rates.put(TaxClass.EXEMPT, null);
        rates.put(TaxClass.OUT_OF_SCOPE, null);
        return new TaxPolicy(mode == PriceTaxMode.INCLUSIVE ? "AE_VAT_CONSUMER_V1" : "AE_VAT_NET_PRICE_V1",
                1, "AE", null, "AED", Instant.parse("2018-01-01T00:00:00Z"), null, mode,
                Set.of("VERIFIED_TEST_CONTEXT"), rates, "VAT_INVOICE_LINE_HALF_UP_V1", "POLICY-EVIDENCE");
    }

    static TaxPolicy seedPolicy(JsonNode profile) {
        var rates = new LinkedHashMap<TaxClass, Integer>();
        for (var taxClass : profile.path("taxClasses")) {
            rates.put(TaxClass.valueOf(taxClass.path("code").asText()), taxClass.path("rateBp").isNull()
                    ? null : taxClass.path("rateBp").asInt());
        }
        var contexts = new LinkedHashSet<String>();
        for (var legal : profile.path("allowedContexts")) {
            contexts.add(legal.asText());
        }
        if (!profile.path("roundingScope").asText().equals("INVOICE_LINE")
                || !profile.path("roundingMode").asText().equals("HALF_UP")) {
            throw new IllegalArgumentException("Fixture algorithm must match declared rounding");
        }
        return new TaxPolicy(profile.path("taxProfileNo").asText(), profile.path("profileVersion").asInt(),
                profile.path("countryCode").asText(), null, profile.path("currency").asText(),
                LocalDate.parse(profile.path("effectiveFrom").asText()).atStartOfDay(ZoneOffset.UTC).toInstant(),
                null, PriceTaxMode.valueOf(profile.path("priceTaxMode").asText()), contexts, rates,
                profile.path("roundingAlgorithmVersion").asText(), "TEST-ADMITTED-SEED");
    }

    static Context context() {
        return context(POINT, TaxPointStatus.CONFIRMED);
    }

    static Context context(Instant point, TaxPointStatus status) {
        return new Context("AE", null, "AE", null, "SUPPLIER-TRN-REF", "VERIFIED_TEST_CONTEXT",
                point, status, "a".repeat(64), 1);
    }

    static Line line(String ref, long amount, TaxClass taxClass, PriceTaxMode mode) {
        return portion(ref, ref, 0, 1, amount, taxClass, mode);
    }

    static Line portion(String source, String invoice, int start, int end, long amount,
            TaxClass taxClass, PriceTaxMode mode) {
        var unit = new PricingUnit(source, null, "G1", "SKU1", "PRICE1", invoice,
                BigDecimal.valueOf(start), BigDecimal.valueOf(end), money(amount), money(amount));
        return new Line(unit, taxClass, mode, money(amount));
    }

    static Adjustment adjustment(TaxPolicy policy, String no, BenefitKind kind, String line,
            long amount, TaxTreatment treatment, AmountBasis basis) {
        return new Adjustment(new Evidence(new AssetRef("OWNER", no), kind, treatment, basis,
                "ENTITY", "CLASSIFICATION-" + no, policy.policyNo(), policy.version()), line, money(amount));
    }

    static FundingPlan funding(long stored, long other) {
        return new FundingPlan(money(stored), money(other));
    }

    static TaxResult finish(TaxEngine.ProvisionalTaxResult provisional, List<Adjustment> adjustments,
            FundingPlan funding, TaxPolicy policy) {
        return new TaxEngine().finalizeTax(new TaxAdjustmentResolver().resolve(
                provisional, adjustments, funding, policy), policy);
    }
}
