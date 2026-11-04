package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.catalog.BusinessEndDeploymentOption;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/** Trusted deployment configuration, never writable through catalog HTTP. */
@ConfigurationProperties("shop.commerce.mall")
public record ShopMallProperties(
        List<BusinessEndDeploymentOption> acceptedDeployments,
        Map<String, Set<String>> requiredEnds,
        Map<String, CoordinatedInventoryService.Binding> inventoryMappings) {
    public ShopMallProperties {
        acceptedDeployments =
                acceptedDeployments == null ? List.of() : List.copyOf(acceptedDeployments);
        requiredEnds = requiredEnds == null ? Map.of() : Map.copyOf(requiredEnds);
        inventoryMappings = inventoryMappings == null ? Map.of() : Map.copyOf(inventoryMappings);
    }
}
