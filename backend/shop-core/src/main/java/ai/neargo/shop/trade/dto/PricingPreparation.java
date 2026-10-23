package ai.neargo.shop.trade.dto;

import ai.neargo.shop.common.pricing.EffectivePricingConfig;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Evidence;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import java.time.Instant;

/** Internal calculation prerequisites only: not a public quote, transaction permission or asset reservation. */
public final class PricingPreparation {
    private PricingPreparation() { }
    public record RuntimeConfig(Scope scope, String industry, String configNo, String revisionNo, long revision,
            String templateRevisionNo, String engineSpecVersion, String ruleCatalogVersion,
            EffectivePricingConfig config, Evidence evidence, String snapshotHash) { }
    public record Prepared(Snapshot source, RuntimeConfig rules, MerchantTaxSnapshot tax,
            GoodsPricingPolicyPort.Snapshot goodsPolicies, Instant expiresAt,
            String preparationHash) { }
}
