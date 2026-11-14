package ai.neargo.shop.food.commerce.dto;

import ai.neargo.shop.food.dto.MenuCatalogPriceContracts;

import java.time.Instant;

/** Policy selection carries original evidence instead of introducing an editable channel price book. */
public final class FoodCatalogPricePolicyContracts {
    private FoodCatalogPricePolicyContracts() {}

    public record Query(FoodCatalogBindingContracts.Query binding, String pricePolicyRef) {}

    public record Facts(
            int schemaVersion, Query query, int policyVersion, String mode,
            FoodCatalogBindingContracts.Facts bindingFacts, MenuCatalogPriceContracts.Facts prices,
            Instant verifiedAt, Instant validUntil, String receiptNo) {}
}
