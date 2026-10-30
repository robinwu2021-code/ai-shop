package ai.neargo.shop.retail.commerce.event;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourceFacts;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.RetailChannelFulfillmentSealed;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * Verifies a committed native receipt before recording durable CIM intent outside the source
 * transaction.
 */
public final class RetailChannelFulfillmentEventConsumer implements OutboxHandler {
    public static final String HANDLER = "commerce-retail-fulfillment-events-v1";
    private final IndustryChannelFulfillmentSourcePort source;
    private final ChannelFulfillmentTriggerPort trigger;
    private final ObjectMapper json;
    private final Clock clock;

    public RetailChannelFulfillmentEventConsumer(
            IndustryChannelFulfillmentSourcePort source,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json,
            Clock clock) {
        this.source = source;
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
        return RetailChannelFulfillmentSealed.TYPE.equals(type);
    }

    @Override
    public DeliveryResult handle(OutboxEnvelope envelope) {
        RetailChannelFulfillmentSealed event;
        try {
            if (!supports(envelope.eventType())
                    || !"PLATFORM".equals(envelope.sourceSystem())
                    || envelope.schemaVersion() != 1)
                return DeliveryResult.failed(
                        "RETAIL_FULFILLMENT_EVENT_SCOPE", "Unsupported original envelope");
            event =
                    CatalogSourceTransportPolicy.read(
                            json,
                            envelope.payload().getBytes(StandardCharsets.UTF_8),
                            RetailChannelFulfillmentSealed.class);
            if (!"MAIN".equals(event.tenantNo())
                    || !event.aggregateType().equals(envelope.aggregateType())
                    || !event.fulfillmentNo().equals(envelope.aggregateId())
                    || !("ORDER:" + event.orderNo()).equals(envelope.streamKey())
                    || !List.of("PICKED_UP", "HANDED_OVER").contains(event.stage())
                    || !"1".equals(event.revision())
                    || event.importRef() == null
                    || event.importRef().isBlank()
                    || event.contentHash() == null
                    || !event.contentHash().matches("[a-f0-9]{64}")
                    || event.receiptNo() == null
                    || event.receiptNo().isBlank()
                    || event.occurredAt() == null
                    || event.occurredAt().isAfter(clock.instant())
                    || envelope.sourceEventNo() == null
                    || envelope.sourceEventNo().isBlank())
                return DeliveryResult.failed(
                        "RETAIL_FULFILLMENT_EVENT_SCOPE", "Original receipt scope mismatch");
        } catch (RuntimeException invalid) {
            return DeliveryResult.failed(
                    "RETAIL_FULFILLMENT_EVENT_INVALID", "Invalid original event payload");
        }
        try {
            var query =
                    new IndustryChannelFulfillmentSourcePort.Query(
                            new Scope(event.tenantNo(), event.entityNo(), event.storeNo()),
                            event.sourceOrderRef(),
                            event.orderNo(),
                            event.fulfillmentNo(),
                            event.revision());
            var facts = source.read(query);
            if (facts == null)
                throw new RetryableOutboxException("Original sealed receipt unavailable");
            if (facts.data() == null || !query.equals(facts.data().query()))
                return DeliveryResult.failed(
                        "RETAIL_FULFILLMENT_RECEIPT_CONFLICT", "Original receipt query differs");
            var data = facts.data();
            if (!event.contentHash().equals(facts.contentHash())
                    || !event.receiptNo().equals(data.receiptNo())
                    || !event.stage().equals(data.stage())
                    || !event.occurredAt().equals(data.occurredAt())
                    || !event.importRef().equals(data.importRef()))
                return DeliveryResult.failed(
                        "RETAIL_FULFILLMENT_RECEIPT_CONFLICT", "Original sealed receipt differs");
            IndustryChannelFulfillmentSourceFacts.validate(query, facts, clock.instant());
            var request =
                    new ChannelFulfillmentTriggerPort.Request(
                            event.entityNo(),
                            event.storeNo(),
                            Owner.RETAIL,
                            event.importRef(),
                            event.sourceOrderRef(),
                            event.orderNo(),
                            event.fulfillmentNo(),
                            event.revision(),
                            envelope.sourceEventNo());
            var result = trigger.record(request);
            if (result == null || !request.equals(result.request()) || result.status() == null)
                throw new RetryableOutboxException("Original CIM receipt unavailable");
            if (result.status() == ChannelFulfillmentTriggerPort.Status.WAITING_IMPORT) {
                if (result.operationNo() != null || result.publicationStatus() != null)
                    throw new RetryableOutboxException(
                            "Pending import returned an invalid receipt");
                return DeliveryResult.retry(
                        "RETAIL_FULFILLMENT_WAITING_IMPORT", "Original import is not confirmed");
            }
            if (result.status() != ChannelFulfillmentTriggerPort.Status.RECORDED
                    || result.operationNo() == null
                    || !result.operationNo().matches("[A-Za-z0-9_-]{1,32}")
                    || result.publicationStatus() == null
                    || !result.publicationStatus().matches("[A-Z_]{1,32}"))
                throw new RetryableOutboxException("Durable original publication is unconfirmed");
            return DeliveryResult.sent();
        } catch (BizException rejected) {
            return switch (rejected.errorCode()) {
                case BAD_REQUEST, FORBIDDEN, CONFLICT ->
                        DeliveryResult.failed(
                                "RETAIL_FULFILLMENT_SOURCE_REJECTED", rejected.errorCode().name());
                default ->
                        DeliveryResult.retry(
                                "RETAIL_FULFILLMENT_SOURCE_UNAVAILABLE",
                                "Original source is unavailable");
            };
        } catch (RuntimeException unresolved) {
            var retry =
                    new RetryableOutboxException(
                            "Original retail receipt or durable CIM intent unresolved");
            retry.initCause(unresolved);
            throw retry;
        }
    }
}
