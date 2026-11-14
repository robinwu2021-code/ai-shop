package ai.neargo.inventory.domain.service;

import java.util.List;
import ai.neargo.inventory.domain.support.SourceStock;

/** Restores posted original stock once, without resolving current warehouse mappings. */
public interface OriginalReservationRestoreService {
    void restore(String purpose, String originalRef, List<ItemQty> items);

    boolean completed(String purpose, String originalRef, List<ItemQty> items);

    /** Frozen source SKU mapping comes from original reservation lines, independent from current ACL. */
    void restoreSourceSkus(String purpose, String originalRef, List<SourceStock.Qty> items);

    boolean completedSourceSkus(String purpose, String originalRef, List<SourceStock.Qty> items);

    record ItemQty(String itemId, int qty) { }
}
