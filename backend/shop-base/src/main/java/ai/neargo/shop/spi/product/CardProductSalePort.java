package ai.neargo.shop.spi.product;

import java.util.Optional;

/** Product owner classifies new paid-card SKUs before checkout can reserve stock or collect payment. */
public interface CardProductSalePort {
    /** Immutable binding data only; no pay lookup may occur while creating order rows. */
    default Optional<SaleDefinition> saleDefinition(String entityNo,String goodsNo,String skuNo) {
        return Optional.empty();
    }
    record SaleDefinition(String templateVersionNo,String currency,int unitsPerItem,String ruleSnapshot) { }
    void requirePurchasable(String entityNo, String goodsNo, String skuNo);
}
