package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;

/** Device lifecycle evidence contains no credential or token hash. */
public record DiningDeviceChanged(
        String entityNo,
        String storeNo,
        String deviceNo,
        String action,
        long version,
        long generation,
        String operationNo)
        implements DomainEvent {
    @Override
    public String aggregateType() {
        return "FOOD_DINING_DEVICE";
    }

    @Override
    public String aggregateId() {
        return deviceNo;
    }

    @Override
    public String eventType() {
        return "FOOD_DINING_DEVICE_CHANGED";
    }
}
