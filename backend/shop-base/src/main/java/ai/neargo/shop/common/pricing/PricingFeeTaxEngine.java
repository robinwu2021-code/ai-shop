package ai.neargo.shop.common.pricing;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Goods and fee tax bases remain separate invoice identities; final taxes allocate to original
 * goods portions.
 */
public final class PricingFeeTaxEngine {
    public record Result(Map<PricingPortion, Long> fees, Map<PricingPortion, Long> taxes) {
        public Result {
            fees = Map.copyOf(fees);
            taxes = Map.copyOf(taxes);
        }
    }

    public Result calculate(
            List<TaxEngine.Line> goods,
            Plan plan,
            MerchantTaxSnapshot tax,
            String sourceHash,
            long version) {
        var lines = new ArrayList<>(goods);
        var originals = new HashMap<PricingPortion, PricingUnit>();
        for (var line : goods) originals.put(PricingPortion.from(line.source()), line.source());
        var fees = new HashMap<PricingPortion, Long>();
        var synthetic = new HashMap<PricingPortion, PricingPortion>();
        if (plan != null) {
            if (!PricingSnapshotHash.of(plan.fees()).equals(plan.planHash()))
                throw new IllegalArgumentException("Fee plan hash mismatch");
            var invoiceTotals = new HashMap<String, Long>();
            for (var resolved : plan.fees())
                for (var entry : resolved.entries())
                    invoiceTotals.merge(entry.invoiceRef(), entry.amountMinor(), Math::addExact);
            for (var resolved : plan.fees())
                for (var entry : resolved.entries()) {
                    var original = originals.get(entry.portion());
                    if (original == null || entry.amountMinor() < 0)
                        throw new IllegalArgumentException("Fee portion missing");
                    String component =
                            "FEE:"
                                    + PricingSnapshotHash.of(
                                                    List.of(
                                                            entry.portion(),
                                                            entry.ruleNo(),
                                                            entry.scopeNo()))
                                            .substring(0, 32);
                    var unit =
                            new PricingUnit(
                                    original.sourceLineNo(),
                                    component,
                                    original.goodsNo(),
                                    original.skuNo(),
                                    entry.revisionNo(),
                                    entry.invoiceRef(),
                                    BigDecimal.ZERO,
                                    BigDecimal.ONE,
                                    new MoneyMinor(
                                            original.baseAmount().currency(), entry.amountMinor()),
                                    new MoneyMinor(
                                            original.baseAmount().currency(), entry.amountMinor()));
                    if (synthetic.put(PricingPortion.from(unit), entry.portion()) != null)
                        throw new IllegalArgumentException("Duplicate fee tax portion");
                    fees.merge(entry.portion(), entry.amountMinor(), Math::addExact);
                    lines.add(
                            new TaxEngine.Line(
                                    unit,
                                    entry.taxClass(),
                                    tax.priceTaxMode(),
                                    new MoneyMinor(
                                            unit.baseAmount().currency(),
                                            invoiceTotals.get(entry.invoiceRef()))));
                }
        }
        var result = new TaxEngine().estimateConfigured(lines, tax, sourceHash, version);
        var taxes = new HashMap<PricingPortion, Long>();
        for (var invoice : result.lines()) {
            var shares =
                    PricingFeeAllocation.allocate(
                            invoice.tax().minor(),
                            invoice.sources().stream().map(u -> u.baseAmount().minor()).toList());
            for (int i = 0; i < invoice.sources().size(); i++) {
                var portion = PricingPortion.from(invoice.sources().get(i));
                var original = synthetic.getOrDefault(portion, portion);
                taxes.merge(original, shares.get(i), Math::addExact);
            }
        }
        return new Result(fees, taxes);
    }
}
