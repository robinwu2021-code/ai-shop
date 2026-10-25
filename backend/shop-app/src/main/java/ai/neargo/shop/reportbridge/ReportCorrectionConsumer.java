package ai.neargo.shop.reportbridge;
import ai.neargo.shop.event.outbox.DeliveryResult;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.spi.report.ReportAffectedDaysPort;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable source-event delivery only marks affected days; the sole daily job does the calculation. */
@Component
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled"},havingValue="true")
@ConditionalOnProperty(prefix="shop.report",name="access-role",havingValue="WRITER",matchIfMissing=true)
public class ReportCorrectionConsumer implements OutboxConsumer {
    private static final Set<String> TYPES=Set.of("ORDER_PAID","SUB_ORDER_PAID","SUB_ORDER_COMPLETED",
            "SUB_ORDER_FULFILLMENT_CHANGED","AFTER_SALE_APPLIED","AFTER_SALE_REJECTED","AFTER_SALE_RETURN_WAIT","AFTER_SALE_REFUNDED");
    private final ReportStorageService storage;
    private final ObjectProvider<ReportAffectedDaysPort> owners;
    private final ObjectMapper json;
    public ReportCorrectionConsumer(ReportStorageService storage,ObjectProvider<ReportAffectedDaysPort> owners,ObjectMapper json) {
        this.storage=storage;this.owners=owners;this.json=json;
    }
    @Override public String handlerId() {return "food-report-correction-v1";}
    @Override public boolean externalWorkflow() {return true;}
    @Override public boolean supports(String type) {return TYPES.contains(type);}
    @Override public DeliveryResult deliver(SysOutbox event) {
        try {consume(event);return DeliveryResult.sent();}
        catch(RuntimeException failure) {return ai.neargo.shop.event.outbox.DeliveryResult.retry("REPORT_CORRECTION_RETRY",failure.getClass().getSimpleName());}
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        if(!supports(event.getEventType())) throw new IllegalArgumentException("REPORT_UNSUPPORTED_EVENT");
        var body=json.readTree(event.getPayload());
        String root=body.path("orderNo").asString("");String sub=body.path("subOrderNo").asString("");
        if(root.isBlank() && sub.isBlank()) throw new IllegalArgumentException("REPORT_CHANGE_REFERENCE_REQUIRED");
        var at=event.getOccurredAt()!=null?event.getOccurredAt():event.getCreatedAt();
        if(at==null) throw new IllegalStateException("REPORT_CHANGE_TIME_REQUIRED");
        var owner=owners.getIfUnique();if(owner==null) throw new IllegalStateException("REPORT_CHANGE_OWNER_NOT_AVAILABLE");
        var affected=owner.affectedDays(new ReportAffectedDaysPort.Change("MAIN",root.isBlank()?null:root,
                sub.isBlank()?null:sub,at.toInstant(ZoneOffset.UTC)));
        for(var day:affected) for(var cursor:storage.scopes(day.scope())) {
            if(cursor.enabled() && !day.businessDate().isBefore(cursor.firstDate()))
                storage.markDirty(new Partition(day.scope(),day.businessDate(),cursor.partition().metricVersion()),
                        event.getEventNo(),"SOURCE_EVENT",at.toInstant(ZoneOffset.UTC));
        }
    }
}
