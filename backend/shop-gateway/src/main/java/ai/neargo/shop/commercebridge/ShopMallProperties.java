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
        Map<String, CoordinatedInventoryService.Binding> inventoryMappings,
        HardwareStock hardwareStock) {
    /**
     * 商城硬件是否接进销存。默认 {@link #UNTRACKED}：先满足商家购买套餐 / 硬件的基本需求，
     * 硬件不占、不扣库存；要接进销存时显式配 {@code shop.commerce.mall.hardware-stock: coordinated}
     * 并配 {@code inventory-mappings}。
     */
    public enum HardwareStock {
        UNTRACKED,
        COORDINATED
    }

    public ShopMallProperties {
        hardwareStock = hardwareStock == null ? HardwareStock.UNTRACKED : hardwareStock;
        acceptedDeployments =
                acceptedDeployments == null ? List.of() : List.copyOf(acceptedDeployments);
        requiredEnds = requiredEnds == null ? Map.of() : Map.copyOf(requiredEnds);
        inventoryMappings = inventoryMappings == null ? Map.of() : Map.copyOf(inventoryMappings);
    }
}
