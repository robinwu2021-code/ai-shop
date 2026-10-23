package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Objects;

/** One original invoice line, even when promotions or AA split its priced source portions. */
public record TaxLineResult(String invoiceLineRef, TaxClass taxClass, PriceTaxMode priceMode, Integer rateBp,
        List<PricingUnit> sources, MoneyMinor price, MoneyMinor net, MoneyMinor tax, MoneyMinor gross) {
    public TaxLineResult {
        PricingPortion.requireIdentity(invoiceLineRef, 64);
        Objects.requireNonNull(taxClass);
        Objects.requireNonNull(priceMode);
        sources = List.copyOf(sources);
        for (var amount : List.of(price, net, tax, gross)) {
            amount.requireNonNegative().requireJsonSafe();
        }
        if (sources.isEmpty() || !net.add(tax).equals(gross)
                || !price.equals(priceMode == PriceTaxMode.INCLUSIVE ? gross : net)
                || sources.stream().anyMatch(source -> !source.taxInvoiceLineRef().equals(invoiceLineRef))) {
            throw new IllegalArgumentException("Original invoice identity and line money must conserve");
        }
    }
}
