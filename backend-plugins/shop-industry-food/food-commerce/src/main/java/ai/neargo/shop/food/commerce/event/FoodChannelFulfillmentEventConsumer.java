package ai.neargo.shop.food.commerce.event;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.policy.DiningChannelFulfillmentSignalPolicy;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * The original committed handover event records one durable CIM intent; retry never means a second
 * provider SEND or local stock acknowledgement.
 */
public final class FoodChannelFulfillmentEventConsumer implements OutboxHandler {
    public static final String HANDLER = "commerce-food-fulfillment-events-v1";
    private final DiningChannelFulfillmentSignalPort signals;
    private final ChannelFulfillmentTriggerPort trigger;
    private final ObjectMapper json;
    private final Clock clock;

    public FoodChannelFulfillmentEventConsumer(
            DiningChannelFulfillmentSignalPort signals,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json) {
        this(signals, trigger, json, Clock.systemUTC());
    }

    public FoodChannelFulfillmentEventConsumer(
            DiningChannelFulfillmentSignalPort signals,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json,
            Clock clock) {
        this.signals = signals;
        this.trigger = trigger;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String handlerId() {
        return HANDLER;
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
    public DeliveryResult handle(OutboxEnvelope envelope) {
        DiningServiceEvent event;
        try {
            if (!supports(envelope.eventType())
                    || !"PLATFORM".equals(envelope.sourceSystem())
                    || envelope.schemaVersion() != 1)
                return DeliveryResult.failed(
                        "FULFILLMENT_EVENT_SCOPE", "Unsupported original event envelope");
            event =
                    CatalogSourceTransportPolicy.read(
                            json,
                            envelope.payload().getBytes(StandardCharsets.UTF_8),
                            DiningServiceEvent.class);
            if (!"FOOD_KITCHEN_TASK".equals(envelope.aggregateType())
                    || !event.taskNo().equals(envelope.aggregateId())
                    || !("FOOD_KITCHEN_TASK:" + event.taskNo()).equals(envelope.streamKey()))
                return DeliveryResult.failed(
                        "FULFILLMENT_EVENT_SCOPE", "Original aggregate scope mismatch");
        } catch (RuntimeException malformed) {
            return DeliveryResult.failed(
                    "FULFILLMENT_EVENT_INVALID", "Invalid original event payload");
        }
        if (!"CUSTODY_HANDOVER".equals(event.action())) return DeliveryResult.sent();
        try {
            var signal =
                    DiningChannelFulfillmentSignalPolicy.validate(
                            event, signals.resolve(event), clock);
            if (signal == null
                    || signal.status() == null
                    || signal.originalEvent() == null
                    || !event.eventNo().equals(signal.originalEvent().eventNo()))
                return DeliveryResult.failed(
                        "FULFILLMENT_SIGNAL_INVALID", "Missing exact original event signal");
            if (signal.status() == DiningChannelFulfillmentSignalPort.Status.NOT_CHANNEL)
                return DeliveryResult.sent();
            if (signal.status() == DiningChannelFulfillmentSignalPort.Status.UNSUPPORTED)
                return DeliveryResult.failed(
                        "FULFILLMENT_SOURCE_UNSUPPORTED",
                        "Original channel fulfillment is unsupported");
            var request =
                    new ChannelFulfillmentTriggerPort.Request(
                            event.entityNo(),
                            event.storeNo(),
                            Owner.FOOD,
                            signal.importNo(),
                            signal.batchNo(),
                            signal.coreOrderNo(),
                            signal.custodyOperationNo(),
                            signal.sourceRevision(),
                            event.eventNo());
            var result = trigger.record(request);
            if (result == null || !request.equals(result.request()) || result.status() == null)
                throw new RetryableOutboxException("Original CIM trigger receipt unavailable");
            if (result.status() == ChannelFulfillmentTriggerPort.Status.WAITING_IMPORT) {
                if (result.operationNo() != null || result.publicationStatus() != null)
                    throw new RetryableOutboxException(
                            "Pending import returned an invalid publication receipt");
                return DeliveryResult.retry(
                        "FULFILLMENT_WAITING_IMPORT",
                        "Actual channel import has not confirmed yet");
            }
            if (result.operationNo() == null
                    || result.operationNo().isBlank()
                    || result.operationNo().length() > 32
                    || result.publicationStatus() == null
                    || !result.publicationStatus().matches("[A-Z_]{1,32}"))
                throw new RetryableOutboxException(
                        "Original durable publication operation is unconfirmed");
            return DeliveryResult.sent();
        } catch (BizException rejected) {
            return switch (rejected.errorCode()) {
                case BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT ->
                        DeliveryResult.failed(
                                "FULFILLMENT_SOURCE_REJECTED", rejected.errorCode().name());
                default ->
                        DeliveryResult.retry(
                                "FULFILLMENT_SOURCE_UNAVAILABLE",
                                "Original fulfillment owner is unavailable");
            };
        } catch (RuntimeException unavailable) {
            var retry =
                    new RetryableOutboxException(
                            "Original fulfillment signal or durable intent remains unresolved");
            retry.initCause(unavailable);
            throw retry;
        }
    }
}
