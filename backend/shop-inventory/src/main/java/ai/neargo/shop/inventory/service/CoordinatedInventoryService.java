package ai.neargo.shop.inventory.service;

/**
 * Inventory-owned durable operation protocol for coordinators that cannot release unknown payments.
 *
 * <p>实现按 ADR-046 落在库存服务：契约 {@code ai.neargo.inventory.api.coordinated.CoordinatedOperations}，
 * 领域 {@code CoordinatedOperationServiceImpl}（迁移 V28，预留生命周期 4），内嵌形态由
 * {@code CoordinatedInventoryServiceImpl} 转接。进销存关着时没有实现 bean，商城硬件商品一律「库存不可用」：
 * 不能下单、不会预留（ShopMallHardwareInventoryAdapter）。
 */
public interface CoordinatedInventoryService {
    record Reference(String client, String externalRef) {}

    record Binding(
            String ownerId,
            String itemId,
            String locationId,
            String mappingVersion,
            String requestReference) {
        public Binding(String ownerId, String itemId, String locationId, String mappingVersion) {
            this(ownerId, itemId, locationId, mappingVersion, null);
        }
    }

    record Reserve(Reference reference, String operationNo, Binding binding, int quantity) {}

    record Shipment(
            Reference reference,
            String operationNo,
            int quantity,
            String carrier,
            String trackingNo,
            String operatorNo) {}

    enum Status {
        RESERVED,
        SHIPPED,
        RELEASED,
        NOT_FOUND
    }

    /**
     * Operation quantity belongs to exactly operationNo, while held/issued are aggregate
     * diagnostics.
     */
    record Evidence(
            Status status,
            String operationNo,
            int operationQuantity,
            int held,
            int issued,
            Binding binding,
            String documentNo) {}

    Evidence reserve(Reserve command);

    Evidence query(Reference reference, String operationNo);

    Evidence release(Reference reference, String originalReserveOperationNo);

    Evidence ship(Shipment command);
}
