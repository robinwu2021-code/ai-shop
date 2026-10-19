package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.order.entity.OrdShipment;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A new historical package has its own dispatch clock; waybill corrections retain the original dispatch clock. */
public final class ReceiptDeadlines {
    private ReceiptDeadlines() { }

    /** Traverse only this child's bounded correction ancestry without inventing carrier pickup facts. */
    public static Long shippedAt(OrdShipment shipment, List<OrdShipment> all) {
        OrdShipment current = shipment;
        Set<String> visited = new HashSet<>();
        Long earliest = null;
        while (current != null && visited.add(current.getShipmentNo()) && visited.size() <= all.size()) {
            if (current.getCreatedAt() != null) {
                long at = current.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                earliest = earliest == null ? at : Math.min(earliest, at);
            }
            String no = current.getShipmentNo();
            current = all.stream().filter(s -> OrdShipment.VOIDED.equals(s.getStatus())
                    && no.equals(s.getSupersededBy())).findFirst().orElse(null);
        }
        return earliest;
    }
}
