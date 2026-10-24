package ai.neargo.shop.food.event;

import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.svc.client.ServiceCallException;
import java.util.LinkedHashSet;
import java.util.function.Supplier;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import tools.jackson.databind.ObjectMapper;

/** Dispatches original kitchen facts, then enqueues only explicitly configured paper routes. */
public class DiningKitchenAcceptedConsumer implements OutboxHandler {
    private final KitchenTaskService kitchen;
    private final ObjectMapper json;
    private final Supplier<PrintPort> printing;
    private final Supplier<FoodPrintService> documents;
    public DiningKitchenAcceptedConsumer(KitchenTaskService kitchen,ObjectMapper json) {
        this(kitchen,json,() -> null,() -> null);
    }
    public DiningKitchenAcceptedConsumer(KitchenTaskService kitchen,ObjectMapper json,
            Supplier<PrintPort> printing,Supplier<FoodPrintService> documents) {
        this.kitchen=kitchen; this.json=json; this.printing=printing; this.documents=documents;
    }
    @Override public String handlerId() { return FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER; }
    @Override public boolean externalWorkflow() { return true; }
    @Override public boolean supports(String type) { return "DINING_ORDER_ACCEPTED".equals(type); }
    @Override public DeliveryResult handle(OutboxEnvelope event) {
        if (!supports(event.eventType())) throw new IllegalArgumentException("Unexpected kitchen event");
        var accepted=json.readValue(event.payload(),DiningOrderAccepted.class);
        if (!"FOOD_BATCH".equals(event.aggregateType()) || !accepted.batchNo().equals(event.aggregateId())
                || event.eventNo()==null || accepted.acceptedAt()==null) {
            throw new IllegalStateException("Invalid original accepted event");
        }
        try {
            // accepted() durably replays the original MAKE ticket before any remote print call.
            var ticket=kitchen.accepted(accepted.entityNo(),accepted.storeNo(),accepted.batchNo(),event.eventNo());
            enqueueConfigured(accepted,ticket);
            return DeliveryResult.sent();
        } catch(ServiceCallException failure) {
            // Enqueue has a permanent original identity, so transport uncertainty is safe to replay.
            var retry=new RetryableOutboxException("Original kitchen/print workflow transport unavailable");
            retry.initCause(failure);
            throw retry;
        }
    }
    private void enqueueConfigured(DiningOrderAccepted accepted,TicketVO ticket) {
        if(ticket==null || !accepted.batchNo().equals(ticket.batchNo()) || !"MAKE".equals(ticket.kind())
                || ticket.ticketNo()==null || ticket.tasks()==null)
            throw new IllegalStateException("Invalid original kitchen ticket");
        var stations=new LinkedHashSet<String>();
        for(var task:ticket.tasks()) {
            if(task==null || !ticket.ticketNo().equals(task.ticketNo())
                    || !accepted.batchNo().equals(task.batchNo()) || !"MAKE".equals(task.ticketKind())
                    || task.stationNo()==null || task.stationNo().isBlank())
                throw new IllegalStateException("Invalid original kitchen station");
            stations.add(task.stationNo());
        }
        var owner=printing.get(); var service=documents.get();
        if(owner==null || service==null) throw new IllegalStateException("Kitchen printing owner unavailable");
        var scope=new PrintPort.Scope(accepted.entityNo(),accepted.storeNo(),"SYSTEM");
        var configuration=owner.configuration(scope);
        if(configuration==null) throw new IllegalStateException("Kitchen print configuration unavailable");
        var requests=new LinkedHashSet<PrintDocumentReq>();
        for(var route:configuration.routes()) {
            if("ORDER".equals(route.scene()) && route.stationNo()==null)
                requests.add(new PrintDocumentReq("ORDER",accepted.batchNo(),null));
            else if("KITCHEN".equals(route.scene()) && stations.contains(route.stationNo()))
                requests.add(new PrintDocumentReq("KITCHEN",accepted.batchNo(),route.stationNo()));
        }
        for(var request:requests) {
            String key="AUTO_PRINT_V1:"+accepted.batchNo()+":"+request.scene()+":"
                    +(request.stationNo()==null?"":request.stationNo());
            service.enqueue(scope,request,key);
        }
    }
}
