package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.CardProductBindingView;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

public interface CardProductBindingService {
    CardProductBindingView bind(EntitlementContext context, String entityNo, ProductEntitlementBinding command,
                                String key);
}
