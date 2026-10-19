package ai.neargo.shop.product.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;

public record ProductEntitlementBinding(String goodsNo, String skuNo, long expectedProductRevision,
                                         String templateVersionNo, int unitsPerItem) {
    public ProductEntitlementBinding {
        ContractChecks.id(goodsNo); ContractChecks.id(skuNo); ContractChecks.id(templateVersionNo);
        ContractChecks.require(expectedProductRevision >= 0 && unitsPerItem >= 1 && unitsPerItem <= 100);
    }
}
