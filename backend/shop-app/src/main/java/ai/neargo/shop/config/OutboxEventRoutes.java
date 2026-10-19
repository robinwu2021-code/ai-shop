package ai.neargo.shop.config;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade.Publication;
import ai.neargo.shop.spi.trade.OrderEvents;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/** Resolves all customer order events onto their root stream; grouped arrivals expand by root. */
public final class OutboxEventRoutes {
    private final ObjectMapper json;
    private final Function<String, String> orderOfSub;
    public OutboxEventRoutes(ObjectMapper json, Function<String, String> orderOfSub) {
        this.json = json; this.orderOfSub = orderOfSub;
    }
    public List<Publication> expand(String source, DomainEvent event) {
        if (event instanceof OrderEvents.SubOrdersArrived arrived) {
            if (arrived.subOrderNos() == null || arrived.subOrderNos().isEmpty()) {
                throw new IllegalArgumentException("Arrivals require sub orders");
            }
            var groups = new LinkedHashMap<String, List<String>>();
            for (String sub : arrived.subOrderNos()) {
                String order = orderOfSub.apply(sub);
                if (order == null || order.isBlank()) { throw new IllegalStateException("Order stream root not found"); }
                groups.computeIfAbsent(order, ignored -> new ArrayList<>()).add(sub);
            }
            return groups.entrySet().stream().map(group -> new Publication(
                    new OrderEvents.SubOrdersArrived(arrived.userNo(), arrived.pickupNo(), List.copyOf(group.getValue())),
                    new PublishOptions("ORDER:" + group.getKey(), 1))).toList();
        }
        var p = json.readTree(json.writeValueAsString(event));
        if ("INVENTORY".equals(source)) {
            String docNo = p.path("docNo").asString("");
            if (docNo.isBlank()) { throw new IllegalArgumentException("Inventory document identity required"); }
            return List.of(new Publication(event, new PublishOptions("DOCUMENT:" + docNo, 2)));
        }
        String orderNo = p.path("orderNo").asString("");
        String subOrderNo = p.path("subOrderNo").asString("");
        boolean logistics = event.eventType().startsWith("WAYBILL_");
        if (subOrderNo.isBlank() && logistics) { subOrderNo = p.path("bizRef").asString(""); }
        if (orderNo.isBlank() && !subOrderNo.isBlank()) {
            String root = orderOfSub.apply(subOrderNo);
            if ((root == null || root.isBlank()) && !logistics) { throw new IllegalStateException("Order stream root not found"); }
            if (root != null) { orderNo = root; }
        }
        String key = orderNo.isBlank() ? event.aggregateType() + ":" + event.aggregateId() : "ORDER:" + orderNo;
        return List.of(new Publication(event, new PublishOptions(key, 1)));
    }
}
