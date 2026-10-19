package ai.neargo.shop.spi.trade;

import java.util.Collection;
import java.util.Set;

/** Batch read of actionable cases; the caller supplies its already authorized merchant/store scope. */
public interface AfterSalePendingQueryPort {
    Set<String> pending(String merchantNo, String storeNo, Collection<String> afterSaleNos);
}
