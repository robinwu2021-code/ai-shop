package ai.neargo.shop.food.event;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.DiningOrderChanged;
import ai.neargo.shop.spi.food.CheckChanged;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.svc.client.ServiceCallException;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 设备出口（TDD-餐饮域迁移新库 §2.8 D-3、D-4）：收原始业务事件（接单、厨房动作、打包交接、改单、账单变化），
 * 让相关的屏刷新，并按打印路由打事件单据（厨房变更、标签、桌台变更）。打印是必达的，所以走发件箱；
 * 屏只收刷新信号，自己拉当前状态。不再写厨显事件表。处理者编号沿用，发件箱里已有的投递记录照旧认领。
 */
public class DiningKdsEventConsumer implements OutboxHandler {
    public static final String HANDLER = "food-kds-business-events-v1";
    private final FoodDeviceRefresh refresh;
    private final ObjectMapper json;
    private final Supplier<PrintPort> printing;
    private final FoodEventPrintDocuments documents;

    public DiningKdsEventConsumer(FoodDeviceRefresh refresh, ObjectMapper json) {
        this(refresh, json, () -> null);
    }

    public DiningKdsEventConsumer(
            FoodDeviceRefresh refresh, ObjectMapper json, Supplier<PrintPort> printing) {
        this.refresh = refresh;
        this.json = json;
        this.printing = printing;
        this.documents = new FoodEventPrintDocuments(json);
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
                        "DINING_CUSTODY_HANDOVER",
                        "FOOD_CHECK_CHANGED",
                        "DINING_ORDER_CHANGED")
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
            case "DINING_SERVICE_EVENT", "DINING_CUSTODY_HANDOVER" -> {
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
            refresh.signal(
                    new FoodDeviceRefresh.Signal(
                            event.entityNo(),
                            event.storeNo(),
                            FoodDeviceRefresh.KITCHEN,
                            event.stationNo() == null ? List.of() : List.of(event.stationNo())));
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
        var owner = printing.get();
        if (owner == null) {
            throw new IllegalStateException("KDS print owner unavailable");
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
            owner.enqueue(
                    scope,
                    documents.freeze(
                            scope,
                            request,
                            "EVENT_PRINT:"
                                    + event.eventNo()
                                    + ":"
                                    + scene
                                    + ":"
                                    + Objects.toString(request.stationNo(), ""),
                            event));
        }
    }

    private static void match(OutboxEnvelope e, String aggregate, String id) {
        if (!aggregate.equals(e.aggregateType()) || !Objects.equals(id, e.aggregateId())) {
            throw new IllegalArgumentException("KDS source identity mismatch");
        }
    }
}
