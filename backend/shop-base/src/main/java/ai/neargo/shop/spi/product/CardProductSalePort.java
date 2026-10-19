package ai.neargo.shop.spi.product;

/** Product owner classifies new paid-card SKUs before checkout can reserve stock or collect payment. */
public interface CardProductSalePort {
    void requirePurchasable(String entityNo, String goodsNo, String skuNo);
}
