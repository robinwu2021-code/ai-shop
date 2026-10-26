package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.trade.CheckoutPort.PromotionOption;

import java.util.List;

/** Freeze quote-only display separately so names/options cannot alter authoritative economic or plan hashes. */
public record CounterQuoteSnapshot(int schema, CheckoutEconomicSnapshot economic, List<PromotionOption> promotions) {
    /** Freeze owner display order while keeping historical schema0 an explicit in-memory representation. */
    public CounterQuoteSnapshot {
        if ((schema != 0 && schema != 1) || economic == null || promotions == null) {
            throw new IllegalArgumentException("invalid counter quote snapshot");
        }
        promotions = List.copyOf(promotions);
    }
}
