package ai.neargo.shop.common.pricing;

import java.util.Objects;

/** Frozen configuration identity, not permission to read a merchant or issue an invoice. */
public record MerchantTaxSnapshot(String tenantNo, String entityNo, String storeNo, long managedVersion,
        Source source, String ownerNo, long sourceVersion, int rateBp, PriceTaxMode priceTaxMode,
        String contentHash) {
    public enum Source { PLATFORM_DEFAULT, MERCHANT_CUSTOM }

    public MerchantTaxSnapshot {
        PricingPortion.requireIdentity(tenantNo, 32);
        PricingPortion.requireIdentity(entityNo, 32);
        PricingPortion.requireIdentity(storeNo, 32);
        Objects.requireNonNull(source);
        if (managedVersion < 0 || managedVersion > 9007199254740991L
                || source == Source.MERCHANT_CUSTOM
                    && (!entityNo.equals(ownerNo) || managedVersion != sourceVersion)
                || source == Source.PLATFORM_DEFAULT && !"PLATFORM".equals(ownerNo)) {
            throw new IllegalArgumentException("Scoped managed and selected source versions required");
        }
        var definition = new TaxSettingDefinition(true, rateBp, priceTaxMode);
        if (!definition.snapshotHash(source + ":" + ownerNo, sourceVersion).equals(contentHash)) {
            throw new IllegalArgumentException("Tax source hash does not match its frozen definition");
        }
    }
}
