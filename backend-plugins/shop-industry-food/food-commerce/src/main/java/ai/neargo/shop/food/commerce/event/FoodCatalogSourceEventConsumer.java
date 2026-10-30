package ai.neargo.shop.food.commerce.event;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxRouteContribution;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.food.ConfigPublished;
import ai.neargo.shop.spi.food.DishChanged;
import ai.neargo.shop.spi.food.MenuChanged;
import ai.neargo.shop.spi.food.ServiceCalendarSaved;
import ai.neargo.shop.spi.food.StorePolicyPublished;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Original typed events become committed reread hints; event content is never copied as a new sale
 * or price fact.
 */
public final class FoodCatalogSourceEventConsumer
        implements OutboxHandler, OutboxRouteContribution {
    public static final String HANDLER = "commerce-food-source-events-v1";
    public static final Set<String> TYPES =
            Set.of(
                    "FOOD_CONFIG_PUBLISHED",
                    "FOOD_DISH_CHANGED",
                    "FOOD_STORE_POLICY_PUBLISHED",
                    "FOOD_SERVICE_CALENDAR_SAVED",
                    "FOOD_MENU_CHANGED");
    private final SourceNoticeService service;
    private final ObjectMapper json;

    public FoodCatalogSourceEventConsumer(SourceNoticeService service, ObjectMapper json) {
        this.service = service;
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
    public boolean supports(String type) {
        return TYPES.contains(type);
    }

    @Override
    public Set<String> consumedArchiveRoutes() {
        return TYPES.stream().map(t -> "PLATFORM:" + t).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public DeliveryResult handle(OutboxEnvelope envelope) {
        if (!"PLATFORM".equals(envelope.sourceSystem())
                || envelope.schemaVersion() != 1
                || !supports(envelope.eventType()))
            throw new IllegalArgumentException("Invalid original source event");
        String entity, store, ref = null, kind = null;
        Instant effective = envelope.occurredAt();
        switch (envelope.eventType()) {
            case "FOOD_MENU_CHANGED" -> {
                var event = json.readValue(envelope.payload(), MenuChanged.class);
                match(envelope, "FOOD_MENU", event.menuNo());
                if (!"DISABLE_MENU".equals(event.action())) return DeliveryResult.sent();
                if (event.after() == null
                        || !"DISABLED".equals(event.after().status())
                        || !event.menuNo().equals(event.after().menuNo())
                        || event.after().version() == null
                        || event.version() != event.after().version())
                    throw new IllegalArgumentException("Missing original disabled menu action");
                entity = event.entityNo();
                store = event.storeNo();
                ref = event.menuNo();
                kind = "WITHDRAWAL";
            }
            case "FOOD_CONFIG_PUBLISHED" -> {
                var event = json.readValue(envelope.payload(), ConfigPublished.class);
                var publication = event.publication();
                match(
                        envelope,
                        "FOOD_CONFIG",
                        publication.configKind() + ":" + publication.configNo());
                entity = event.entityNo();
                store = event.storeNo();
                if ("MENU".equals(publication.configKind())) ref = publication.configNo();
                if (publication.effectiveAt() == null)
                    throw new IllegalArgumentException("Missing source effective date");
                effective = publication.effectiveAt().toInstant();
            }
            case "FOOD_DISH_CHANGED" -> {
                var event = json.readValue(envelope.payload(), DishChanged.class);
                match(envelope, "FOOD_DISH", event.goodsNo());
                entity = event.entityNo();
                store = event.storeNo();
            }
            case "FOOD_STORE_POLICY_PUBLISHED" -> {
                var event = json.readValue(envelope.payload(), StorePolicyPublished.class);
                match(envelope, "FOOD_STORE_POLICY", event.storeNo());
                entity = event.entityNo();
                store = event.storeNo();
                if (event.effectiveFrom() == null)
                    throw new IllegalArgumentException("Missing policy effective date");
                effective = event.effectiveFrom();
            }
            case "FOOD_SERVICE_CALENDAR_SAVED" -> {
                var event = json.readValue(envelope.payload(), ServiceCalendarSaved.class);
                match(envelope, "FOOD_SERVICE_CALENDAR", event.calendarNo());
                entity = event.entityNo();
                store = event.storeNo();
            }
            default -> throw new IllegalArgumentException("Unknown event");
        }
        service.receive(
                new Notice(
                        "PLATFORM",
                        envelope.sourceEventNo(),
                        entity,
                        store,
                        Owner.FOOD,
                        ref,
                        effective,
                        kind));
        return DeliveryResult.sent();
    }

    private static void match(OutboxEnvelope e, String aggregate, String number) {
        if (!aggregate.equals(e.aggregateType()) || !number.equals(e.aggregateId()))
            throw new IllegalArgumentException("Original event identity mismatch");
    }
}
