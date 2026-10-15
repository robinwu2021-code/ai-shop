package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.StockPort.SkuQty;
import java.util.List;

/** Restores stored deduction sources; changing today's inventory configuration cannot redirect historical returns. */
public interface OriginalStockRestoreService {
    void restore(String purpose, String originalLockNo, List<SkuQty> items);
}
