package ai.neargo.shop.spi.trade;

import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.common.pricing.TaxEngine.ConfiguredTaxResult;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort.Scope;

/** Industry callers supply owner-admitted complete invoice facts, never unverified client amounts. */
public interface PricingTaxCalculationPort {
    Calculation estimate(Scope scope, Input input);

    /** Re-resolves current configuration and facts; this does not debit funds or persist an order. */
    Calculation confirm(Scope scope, Input current, Calculation expected);

    record Input(String sourceSnapshotHash, long sourceVersion, List<TaxEngine.Line> lines) {
        public Input {
            Objects.requireNonNull(lines);
            if (lines.isEmpty() || lines.size() > 1024) throw new IllegalArgumentException("Bounded lines required");
            lines = List.copyOf(lines);
            new PricingFacts(lines.getFirst().source().baseAmount().currency(), sourceSnapshotHash, sourceVersion,
                    lines.stream().map(TaxEngine.Line::source).toList());
        }
    }

    record Calculation(Input input, ConfiguredTaxResult result) {
        public Calculation { Objects.requireNonNull(input); Objects.requireNonNull(result); }
    }
}
