package ai.neargo.shop.food.event;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.spi.food.FoodOutboxRouting;

import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Durable START events record production theory; missing exclusive stock-plan proof stays
 * explicitly BLOCKED.
 *
 * <p>As an external workflow, a raw exception would be recorded OUTCOME_UNKNOWN and never retried.
 * consume() is keyed by the original event and re-queries inventory before issuing, so replay is
 * safe: transport/unknown-outcome failures and pending receipts are retried. Deterministic
 * outcomes (business rejection, durable BLOCKED receipt) end FAILED with a named code, visible on
 * the delivery record and replayable by ops once the cause is fixed — never retried forever.
 */
public class DiningProductionConsumer implements OutboxHandler {
    private final ProductionConsumptionService production;
    private final ObjectMapper json;

    public DiningProductionConsumer(ProductionConsumptionService production, ObjectMapper json) {
        this.production = production;
        this.json = json;
    }

    @Override
    public String handlerId() {
        return FoodOutboxRouting.PRODUCTION_HANDLER;
    }

    @Override
    public boolean externalWorkflow() {
        return true;
    }

    @Override
    public boolean supports(String type) {
        return "DINING_SERVICE_EVENT".equals(type);
    }

    @Override
    public DeliveryResult handle(OutboxEnvelope event) {
        if (!supports(event.eventType()))
            throw new IllegalArgumentException("Unexpected production event");
        var source = json.readValue(event.payload(), DiningServiceEvent.class);
        if (!"FOOD_KITCHEN_TASK".equals(event.aggregateType())
                || !source.taskNo().equals(event.aggregateId())
                || event.eventNo() == null
                || source.eventNo() == null
                || source.occurredAt() == null)
            throw new IllegalStateException("Invalid original production event");
        if (!Set.of("START", "MATERIAL_SUPPLEMENT", "MATERIAL_WASTE").contains(source.action())
                || !"MAKE".equals(source.taskRole())) return DeliveryResult.sent();
        ConsumptionVO receipt;
        try {
            receipt =
                    production.consume(
                            source.entityNo(),
                            source.storeNo(),
                            source.actorNo(),
                            source.eventNo());
        } catch (BizException rejected) {
            return DeliveryResult.failed("PRODUCTION_REJECTED", rejected.errorCode().name());
        } catch (RuntimeException uncertain) {
            throw retry("Production consumption outcome unknown", uncertain);
        }
        return switch (receipt.status()) {
            case "CONFIRMED", "NOT_REQUIRED", "REVERSED" -> DeliveryResult.sent();
            case "BLOCKED" -> DeliveryResult.failed("PRODUCTION_BLOCKED", receipt.consumptionNo());
            default -> throw retry("Production issue remains unresolved", null);
        };
    }

    private static RetryableOutboxException retry(String message, Throwable cause) {
        var retry = new RetryableOutboxException(message);
        if (cause != null) retry.initCause(cause);
        return retry;
    }
}
