package ai.neargo.inventory.domain.service;

import java.util.List;

/** Restores posted original stock once, without resolving current warehouse mappings. */
public interface OriginalReservationRestoreService {
    void restore(String purpose, String originalRef, List<ItemQty> items);

    boolean completed(String purpose, String originalRef, List<ItemQty> items);

    record ItemQty(String itemId, int qty) { }
}
