package ai.neargo.shop.trade.dto;

import ai.neargo.shop.common.pricing.PriceTaxMode;

/** Internal requests use the existing trusted pricing Context; none of these types authenticates a caller. */
public final class PricingTaxSettingCommands {
    private PricingTaxSettingCommands() { }

    public enum Source { PLATFORM_DEFAULT, MERCHANT_CUSTOM }
    public record SaveReq(long expectedVersion, int rateBp, PriceTaxMode priceTaxMode) { }
    public record ResetReq(long expectedVersion) { }
    public record Snapshot(Source source, String ownerNo, long version, int rateBp,
            PriceTaxMode priceTaxMode, String contentHash) { }
    /** Version belongs to the managed setting, while effective.version belongs to its selected source. */
    public record SettingVO(long version, boolean custom, Snapshot effective) { }
}
