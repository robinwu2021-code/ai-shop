package ai.neargo.shop.inventory.service;

/**
 * Inventory-owned durable operation protocol for coordinators that cannot release unknown payments.
 *
 * <p>2026-11-04：合入统一项目线时只保留契约、暂不提供实现。进销存已拆成独立服务（ADR-046），
 * 原实现依赖旧模块的 mapper 与生命周期 3（统一线里已另有含义），要按库存客户端重新实现。
 * 没有实现 bean 时商城硬件商品一律「库存不可用」：不能下单、不会预留（ShopMallHardwareInventoryAdapter）。
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
