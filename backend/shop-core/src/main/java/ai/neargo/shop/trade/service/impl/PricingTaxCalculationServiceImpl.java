package ai.neargo.shop.trade.service.impl;

import java.util.Objects;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.spi.trade.PricingTaxCalculationPort.Calculation;
import ai.neargo.shop.spi.trade.PricingTaxCalculationPort.Input;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort.Scope;
import ai.neargo.shop.trade.service.PricingTaxCalculationService;
import org.springframework.stereotype.Service;

/** Both preview and confirmation use the same backend source and exact engine implementation. */
@Service
public class PricingTaxCalculationServiceImpl implements PricingTaxCalculationService {
    private final PricingTaxSettingPort settings;
    private final TaxEngine engine = new TaxEngine();

    public PricingTaxCalculationServiceImpl(PricingTaxSettingPort settings) { this.settings = settings; }

    @Override
    public Calculation estimate(Scope scope, Input input) {
        Objects.requireNonNull(input);
        return calculate(input, settings.resolve(scope));
    }

    @Override
    public Calculation confirm(Scope scope, Input current, Calculation expected) {
        if (expected == null || !expected.input().equals(current)) throw conflict();
        var snapshot = settings.resolve(scope);
        if (!snapshot.equals(expected.result().snapshot())) throw conflict();
        // Never consume caller-carried totals: recompute from current owner facts and the re-resolved source.
        return calculate(current, snapshot);
    }

    private Calculation calculate(Input input, MerchantTaxSnapshot snapshot) {
        return new Calculation(input, engine.estimateConfigured(input.lines(), snapshot,
                input.sourceSnapshotHash(), input.sourceVersion()));
    }

    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
