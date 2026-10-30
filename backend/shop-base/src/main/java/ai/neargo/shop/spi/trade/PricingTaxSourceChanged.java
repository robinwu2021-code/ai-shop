package ai.neargo.shop.spi.trade;

import ai.neargo.shop.event.DomainEvent;

/**
 * Original committed tax-source configuration hint; no caller amounts or replacement tax authority
 * are carried.
 */
public record PricingTaxSourceChanged(
        String tenantNo,
        String entityNo,
        String settingKey,
        long version,
        String contentHash,
        String actorNo)
        implements DomainEvent {
    @Override
    public String aggregateType() {
        return "PRICING_TAX_SETTING";
    }

    @Override
    public String aggregateId() {
        return settingKey;
    }

    @Override
    public String eventType() {
        return "PRICING_TAX_SOURCE_CHANGED";
    }
}
