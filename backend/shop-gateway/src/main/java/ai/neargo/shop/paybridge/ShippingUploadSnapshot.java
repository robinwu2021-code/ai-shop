package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.trade.WxShippingPort;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/** Stable sent-package set belongs to the payment queue revision, never to mutable package CAS versions. */
public record ShippingUploadSnapshot(List<WxShippingPort.ShippingItem> shipments, int deliveryMode,
                                     boolean allDelivered, String blocker) {
    public ShippingUploadSnapshot { shipments = List.copyOf(shipments); }

    public String json(ObjectMapper json) { return json.writeValueAsString(this); }

    public static ShippingUploadSnapshot parse(String raw, ObjectMapper json) {
        if (raw == null) { return null; }
        // H2 returns a JSON string scalar for setString; MySQL returns the object text.
        String payload = raw.startsWith("\"") ? json.readValue(raw, String.class) : raw;
        return json.readValue(payload, ShippingUploadSnapshot.class);
    }

    public boolean contains(String subNo, String sourceNo, Long generation, String carrier, String number) {
        return shipments.stream().anyMatch(s -> Objects.equals(subNo, s.subOrderNo())
                && Objects.equals(sourceNo, s.sourceShipmentNo())
                && Objects.equals(generation, s.sourceShipmentVersion())
                && Objects.equals(carrier, s.expressCompany()) && Objects.equals(number, s.trackingNo()));
    }
}
