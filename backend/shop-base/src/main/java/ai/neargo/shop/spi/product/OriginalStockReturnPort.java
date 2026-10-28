package ai.neargo.shop.spi.product;

import java.util.List;

/**
 * Resolves the persisted source authority before requesting a return; accepted DUAL work remains its durable outbox.
 */
public interface OriginalStockReturnPort {
    StockPort.RestoreResult restore(String restoreNo, String originalOrderNo, List<StockPort.SkuQty> items);
}
