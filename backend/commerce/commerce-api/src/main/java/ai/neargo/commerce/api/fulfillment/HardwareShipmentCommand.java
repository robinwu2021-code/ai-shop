package ai.neargo.commerce.api.fulfillment;

import java.util.HashSet;
import java.util.List;

public record HardwareShipmentCommand(
        String tenantNo, String orderNo, String orderComponentNo, String operationNo,
        String carrierCode, String trackingNo, long quantity, String operatorNo, List<String> deviceSerials) {
    public HardwareShipmentCommand {
        deviceSerials = deviceSerials == null ? List.of() : List.copyOf(deviceSerials);
        if (!deviceSerials.isEmpty() && (deviceSerials.size() != quantity
                || new HashSet<>(deviceSerials).size() != deviceSerials.size()
                || deviceSerials.stream().anyMatch(s -> s.isBlank() || s.length() > 64)))
            throw new IllegalArgumentException("invalid shipment serial numbers");
    }

    public HardwareShipmentCommand(String tenant, String order, String component, String operation,
            String carrier, String tracking, long quantity, String operator) {
        this(tenant, order, component, operation, carrier, tracking, quantity, operator, List.of());
    }

    public HardwareShipmentCommand(String tenant, String order, String component, String operation,
            String carrier, String tracking, long quantity) {
        this(tenant, order, component, operation, carrier, tracking, quantity, null);
    }
}
