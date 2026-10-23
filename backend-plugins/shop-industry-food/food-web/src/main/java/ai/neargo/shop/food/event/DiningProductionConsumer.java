package ai.neargo.shop.food.event;

import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import tools.jackson.databind.ObjectMapper;

/** Durable START events record production theory; missing exclusive stock-plan proof stays explicitly BLOCKED. */
public class DiningProductionConsumer implements OutboxHandler {
    private final ProductionConsumptionService production;
    private final ObjectMapper json;
    public DiningProductionConsumer(ProductionConsumptionService production,ObjectMapper json) {
        this.production=production; this.json=json;
    }
    @Override public String handlerId() { return FoodOutboxRouting.PRODUCTION_HANDLER; }
    @Override public boolean externalWorkflow() { return true; }
    @Override public boolean supports(String type) { return "DINING_SERVICE_EVENT".equals(type); }
    @Override public DeliveryResult handle(OutboxEnvelope event) {
        if(!supports(event.eventType())) throw new IllegalArgumentException("Unexpected production event");
        var source=json.readValue(event.payload(),DiningServiceEvent.class);
        if(!"FOOD_KITCHEN_TASK".equals(event.aggregateType()) || !source.taskNo().equals(event.aggregateId())
                || event.eventNo()==null || source.eventNo()==null || source.occurredAt()==null)
            throw new IllegalStateException("Invalid original production event");
        if(!"START".equals(source.action()) || !"MAKE".equals(source.taskRole())) return DeliveryResult.sent();
        var receipt=production.consume(source.entityNo(),source.storeNo(),source.actorNo(),source.eventNo());
        if(!"CONFIRMED".equals(receipt.status()) && !"BLOCKED".equals(receipt.status())) throw new IllegalStateException("Production issue remains unresolved");
        return DeliveryResult.sent();
    }
}
