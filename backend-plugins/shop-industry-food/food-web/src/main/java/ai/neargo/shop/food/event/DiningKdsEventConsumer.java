package ai.neargo.shop.food.event;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.spi.DiningOrderChanged;
import ai.neargo.shop.spi.food.CheckChanged;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.spi.food.DiningStationRuntimeChanged;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.svc.client.ServiceCallException;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Projects original durable domain events; never polls queue state or emits invented business
 * transitions.
 */
public class DiningKdsEventConsumer implements OutboxHandler {
    public static final String HANDLER = "food-kds-business-events-v1";
    private final FoodKdsService service;
    private final ObjectMapper json;
    private final Supplier<PrintPort> printing;
    private final Supplier<FoodPrintService> documents;

    public DiningKdsEventConsumer(FoodKdsService service, ObjectMapper json) {
        this(service, json, () -> null, () -> null);
    }

    public DiningKdsEventConsumer(
            FoodKdsService service,
            ObjectMapper json,
            Supplier<PrintPort> printing,
            Supplier<FoodPrintService> documents) {
        this.service = service;
        this.json = json;
        this.printing = printing;
        this.documents = documents;
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
        return Set.of(
                        "DINING_ORDER_ACCEPTED",
                        "DINING_SERVICE_EVENT",
                        "FOOD_CHECK_CHANGED",
                        "DINING_ORDER_CHANGED",
                        "DINING_STATION_RUNTIME_CHANGED")
                .contains(type);
    }

    @Override
    public DeliveryResult handle(OutboxEnvelope envelope) {
        if (!supports(envelope.eventType())
                || envelope.eventNo() == null
                || envelope.occurredAt() == null) {
            throw new IllegalArgumentException("Invalid KDS original event");
        }
        EventDraft event;
        var body = new LinkedHashMap<String, Object>();
        switch (envelope.eventType()) {
            case "DINING_ORDER_ACCEPTED" -> {
                var source = json.readValue(envelope.payload(), DiningOrderAccepted.class);
                match(envelope, "FOOD_BATCH", source.batchNo());
                body.put("batchNo", source.batchNo());
                body.put("checkNo", source.checkNo());
                body.put("action", "ACCEPTED");
                body.put("fulfillment", source.fulfillment());
                event =
                        draft(
                                envelope,
                                source.entityNo(),
                                source.storeNo(),
                                null,
                                "ORDER_ACCEPTED",
                                source.batchNo(),
                                envelope.streamSequence(),
                                body);
            }
            case "DINING_SERVICE_EVENT" -> {
                var source = json.readValue(envelope.payload(), DiningServiceEvent.class);
                match(envelope, "FOOD_KITCHEN_TASK", source.taskNo());
                body.put("batchNo", source.batchNo());
                body.put("ticketNo", source.ticketNo());
                body.put("taskNo", source.taskNo());
                body.put("foodLineNo", source.foodLineNo());
                body.put("componentNo", source.componentNo());
                body.put("taskRole", source.taskRole());
                body.put("routeVersion", source.routeVersion());
                body.put("action", source.action());
                body.put("qty", source.qty());
                body.put("reason", source.reason());
                body.put("sourceEventNo", source.eventNo());
                event =
                        draft(
                                envelope,
                                source.entityNo(),
                                source.storeNo(),
                                source.stationNo(),
                                "KITCHEN_ACTION",
                                source.taskNo(),
                                envelope.streamSequence(),
                                body);
            }
            case "DINING_ORDER_CHANGED" -> {
                var source = json.readValue(envelope.payload(), DiningOrderChanged.class);
                match(envelope, "FOOD_BATCH", source.batchNo());
                body.put("checkNo", source.checkNo());
                body.put("batchNo", source.batchNo());
                body.put("changeNo", source.changeNo());
                body.put("action", source.action());
                body.put("foodLineNos", source.foodLineNos());
                event =
                        draft(
                                envelope,
                                source.entityNo(),
                                source.storeNo(),
                                null,
                                "ORDER_CHANGED",
                                source.batchNo(),
                                source.sourceVersion(),
                                body);
            }
            case "DINING_STATION_RUNTIME_CHANGED" -> {
                var source = json.readValue(envelope.payload(), DiningStationRuntimeChanged.class);
                match(envelope, "FOOD_STATION_RUNTIME", source.stationNo());
                body.put("stationNo", source.stationNo());
                body.put("commandNo", source.commandNo());
                body.put("action", source.action());
                body.put("status", source.status());
                body.put("reason", source.reason());
                event =
                        draft(
                                envelope,
                                source.entityNo(),
                                source.storeNo(),
                                source.stationNo(),
                                "CALL".equals(source.action()) ? "VOICE_CALL" : "STATION_CONTROL",
                                source.commandNo(),
                                source.sourceVersion(),
                                body);
            }
            default -> {
                var source = json.readValue(envelope.payload(), CheckChanged.class);
                match(envelope, "FOOD_CHECK", source.checkNo());
                body.put("checkNo", source.checkNo());
                body.put("action", source.action());
                body.put("operationNo", source.operationNo());
                body.put("before", source.before());
                body.put("after", source.after());
                body.put("beforeSeats", source.beforeSeats());
                body.put("afterSeats", source.afterSeats());
                body.put("reason", source.reason());
                event =
                        draft(
                                envelope,
                                source.entityNo(),
                                source.storeNo(),
                                null,
                                "CHECK_CHANGED",
                                source.checkNo(),
                                source.version(),
                                body);
            }
        }
        try {
            service.append(event);
            enqueue(event);
            return DeliveryResult.sent();
        } catch (ServiceCallException failure) {
            var retry = new RetryableOutboxException("KDS original event transport unavailable");
            retry.initCause(failure);
            throw retry;
        }
    }

    private EventDraft draft(
            OutboxEnvelope e,
            String entity,
            String store,
            String station,
            String type,
            String no,
            long version,
            Object body) {
        return new EventDraft(
                e.eventNo(),
                entity,
                store,
                station,
                type,
                no,
                version,
                json.writeValueAsString(body),
                e.occurredAt().toString());
    }

    private void enqueue(EventDraft event) {
        var owner = printing.get();
        var service = documents.get();
        if (owner == null || service == null) {
            throw new IllegalStateException("KDS print owner unavailable");
        }
        String scene =
                switch (event.eventType()) {
                    case "KITCHEN_ACTION" -> "KITCHEN_CHANGE";
                    case "CHECK_CHANGED" -> "TABLE_CHANGE";
                    case "ORDER_CHANGED" -> "KITCHEN_CHANGE";
                    default -> null;
                };
        if (scene == null) {
            return;
        }
        var node = json.readTree(event.payload());
        String action = node.path("action").asString("");
        if ("KITCHEN_CHANGE".equals(scene) && "ORDER_CHANGED".equals(event.eventType())) {
            /* Business request notification; no assertion of kitchen VOID or financial completion. */
        } else if ("KITCHEN_CHANGE".equals(scene) && "READY".equals(action)) {
            scene = "LABEL";
        } else if ("KITCHEN_CHANGE".equals(scene)
                && !Set.of("VOID", "REMAKE", "LOSS").contains(action)) {
            return;
        }
        if ("TABLE_CHANGE".equals(scene)
                && !Set.of("TRANSFER", "COMBINE_TABLES", "MERGE", "SPLIT").contains(action)) {
            return;
        }
        var scope = new PrintPort.Scope(event.entityNo(), event.storeNo(), "SYSTEM");
        var requests = new LinkedHashSet<PrintDocumentReq>();
        for (var route : owner.configuration(scope).routes()) {
            if (scene.equals(route.scene())
                    && (event.stationNo() == null
                            || Objects.equals(event.stationNo(), route.stationNo()))) {
                requests.add(new PrintDocumentReq(scene, event.eventNo(), route.stationNo()));
            }
        }
        for (var request : requests) {
            service.enqueue(
                    scope,
                    request,
                    "EVENT_PRINT:"
                            + event.eventNo()
                            + ":"
                            + scene
                            + ":"
                            + Objects.toString(request.stationNo(), ""));
        }
    }

    private static void match(OutboxEnvelope e, String aggregate, String id) {
        if (!aggregate.equals(e.aggregateType()) || !Objects.equals(id, e.aggregateId())) {
            throw new IllegalArgumentException("KDS source identity mismatch");
        }
    }
}
