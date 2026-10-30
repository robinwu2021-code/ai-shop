package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.StockPort.SkuQty;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import java.util.List;

/** Restores stored deduction sources; changing today's inventory configuration cannot redirect historical returns. */
public interface OriginalStockRestoreService {
    void restore(String purpose, String originalLockNo, List<SkuQty> items);
    /** Validate the complete frozen original under its stock roots before any restoration effect. */
    void restore(String purpose, Original original, String confirmationOperationNo, List<SkuQty> items);
}
