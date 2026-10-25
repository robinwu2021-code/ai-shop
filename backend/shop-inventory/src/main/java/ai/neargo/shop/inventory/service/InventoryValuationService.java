package ai.neargo.shop.inventory.service;

/**
 * Inventory-owned valuation metadata; historical posting currencies and batch costs never change.
 */
public interface InventoryValuationService {
    record Policy(
            long version,
            String currency,
            String costMethod,
            Long unitCostMinor,
            boolean batchTracked) {}

    Policy configure(String owner, String item, String actor, Policy request, String key);

    Policy get(String owner, String item);
}
