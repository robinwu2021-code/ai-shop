package ai.neargo.shop.trade.dto;

import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingBenefitBasis;
import java.util.Objects;

/** Frozen undiscounted quote basis, with gross payment amount kept separate from eligible source prices. */
public record PricingFundingBasis(int schemaVersion, PricingBenefitBasis.Snapshot benefits, MerchantTaxSnapshot tax,
        long quoteNetMinor, long quoteTaxMinor, long quoteReceivableMinor, String quoteEconomicsHash) {
    public PricingFundingBasis {
        Objects.requireNonNull(tax);
        PricingBenefitBasis.validate(benefits);
        for (long value : new long[] {quoteNetMinor, quoteTaxMinor, quoteReceivableMinor}) {
            new MoneyMinor(benefits.currency(), value).requireNonNegative().requireJsonSafe();
        }
        long sourcePrice = tax.priceTaxMode() == PriceTaxMode.INCLUSIVE ? quoteReceivableMinor : quoteNetMinor;
        if (schemaVersion != 1 || Math.addExact(quoteNetMinor, quoteTaxMinor) != quoteReceivableMinor
                || sourcePrice != benefits.priceTotalMinor() || quoteEconomicsHash == null
                || !quoteEconomicsHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Original quote tax and source-price basis must conserve");
        }
    }
}
