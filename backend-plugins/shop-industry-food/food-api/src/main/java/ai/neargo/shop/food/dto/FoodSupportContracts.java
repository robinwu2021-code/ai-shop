package ai.neargo.shop.food.dto;

import java.util.List;
import java.util.Map;

/** Read-only runtime evidence; readiness does not assert external owner connectivity. */
public final class FoodSupportContracts {
    private FoodSupportContracts() {}

    public record ReadinessVO(
            String deployment,
            boolean ready,
            boolean schemaValidated,
            String schemaVersion,
            List<String> missingCapabilities,
            Map<String, Integer> typedProviderCounts) {
        public ReadinessVO {
            missingCapabilities = List.copyOf(missingCapabilities);
            typedProviderCounts = Map.copyOf(typedProviderCounts);
        }
    }
}
