package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxRouteContribution;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.product.ProductEvents;

import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Product changes fan out reread hints to both industries; old event fields never become a new
 * inventory or price fact.
 */
public final class CommerceProductSourceEventConsumer
        implements OutboxHandler, OutboxRouteContribution {
    public static final String HANDLER = "commerce-product-source-events-v1";
    private static final Set<String> TYPES =
            Set.of(
                    "SKU_UPSERTED",
                    "SKU_RETIRED",
                    "SKU_INV_MODE_CHANGED",
                    "GOODS_ON_SALE_CHANGED",
                    "CATALOG_SOURCE_CHANGED");
    private final SourceNoticeService notices;
    private final ObjectMapper json;

    public CommerceProductSourceEventConsumer(SourceNoticeService notices, ObjectMapper json) {
        this.notices = notices;
        this.json = json;
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
    public boolean supports(String eventType) {
        return TYPES.contains(eventType);
    }

    @Override
    public Set<String> consumedArchiveRoutes() {
        return Set.of("PLATFORM:CATALOG_SOURCE_CHANGED");
    }

    @Override
    public DeliveryResult handle(OutboxEnvelope envelope) {
        if (!"PLATFORM".equals(envelope.sourceSystem())
                || envelope.schemaVersion() != 1
                || !supports(envelope.eventType()))
            throw new IllegalArgumentException("Invalid original product event");
        String entity, store = null;
        switch (envelope.eventType()) {
            case "SKU_UPSERTED" -> {
                var event = json.readValue(envelope.payload(), ProductEvents.SkuUpserted.class);
                match(envelope, "SKU", event.skuNo());
                entity = event.entityNo();
            }
            case "SKU_RETIRED" -> {
                var event = json.readValue(envelope.payload(), ProductEvents.SkuRetired.class);
                match(envelope, "SKU", event.skuNo());
                entity = event.entityNo();
            }
            case "SKU_INV_MODE_CHANGED" -> {
                var event =
                        json.readValue(envelope.payload(), ProductEvents.SkuInvModeChanged.class);
                match(envelope, "SKU", event.skuNo());
                entity = event.entityNo();
            }
            case "GOODS_ON_SALE_CHANGED" -> {
                var event =
                        json.readValue(envelope.payload(), ProductEvents.GoodsOnSaleChanged.class);
                match(envelope, "GOODS", event.goodsNo());
                entity = event.entityNo();
            }
            case "CATALOG_SOURCE_CHANGED" -> {
                var event =
                        json.readValue(
                                envelope.payload(), ProductEvents.CatalogSourceChanged.class);
                match(envelope, "GOODS", event.goodsNo());
                entity = event.entityNo();
                store = event.storeNo();
            }
            default -> throw new IllegalArgumentException("Unsupported original event");
        }
        for (var owner : Owner.values())
            notices.receive(
                    new Notice(
                            "PLATFORM",
                            envelope.sourceEventNo(),
                            entity,
                            store,
                            owner,
                            null,
                            envelope.occurredAt()));
        return DeliveryResult.sent();
    }

    private static void match(OutboxEnvelope envelope, String aggregate, String number) {
        if (!aggregate.equals(envelope.aggregateType()) || !envelope.aggregateId().equals(number))
            throw new IllegalArgumentException("Product event original identity mismatch");
    }
}
