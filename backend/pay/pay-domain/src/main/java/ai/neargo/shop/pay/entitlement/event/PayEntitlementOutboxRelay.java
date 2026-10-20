package ai.neargo.shop.pay.entitlement.event;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import tools.jackson.databind.ObjectMapper;

/** Bridges immutable pay events outside financial transactions; recovery never re-runs asset commands. */
public class PayEntitlementOutboxRelay {
    private static final int MAX_BRIDGE_ATTEMPTS = 10;
    private static final Logger log = LoggerFactory.getLogger(PayEntitlementOutboxRelay.class);
    private static final String TENANT = "MAIN";
    private static final int BATCH_SIZE = 100;
    private final PayEntOutboxMapper outbox;
    private final PayEntitlementEventBridgePort bridge;
    private final ObjectMapper json;
    private final EntitlementEventDeliveryService delivery;
    private long recoveryCursor;
    private final ScheduledExecutorService retryTimer = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "pay-entitlement-outbox-retry"));
    private final ExecutorService relayWorker = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "pay-entitlement-outbox-bridge"));

    public PayEntitlementOutboxRelay(PayEntOutboxMapper outbox, PayEntitlementEventBridgePort bridge,ObjectMapper json) {
        this(outbox,bridge,json,null);
    }
    public PayEntitlementOutboxRelay(PayEntOutboxMapper outbox,PayEntitlementEventBridgePort bridge,
            ObjectMapper json,EntitlementEventDeliveryService delivery) {
        this.outbox=outbox; this.bridge=bridge; this.json=json; this.delivery=delivery;
    }
    /** Existing native fixtures use a real core bus directly; production always binds the owned bridge. */
    public PayEntitlementOutboxRelay(PayEntOutboxMapper outbox, OutboxEventBus bus,ObjectMapper json) {
        this(outbox,bus::publish,json);
    }
    public void relay(String eventNo) {
        var row=outbox.findByEventNo(TENANT,eventNo);
        if(row==null || !"PENDING".equals(row.getStatus())) return;
        try {
            var event=PayEntitlementOutboxCodec.decode(row,json);
            bridge.publish(eventNo,event);
            outbox.markForwarded(row.getId(),TENANT,LocalDateTime.now(ZoneOffset.UTC));
        } catch(RuntimeException failure) {
            var now=LocalDateTime.now(ZoneOffset.UTC);
            int attempts=row.getAttempts()==null?0:row.getAttempts();
            if(attempts+1>=MAX_BRIDGE_ATTEMPTS) {
                if(outbox.markDead(row.getId(),TENANT,now,row.getVersion())==1) {
                    recoverDelivery(eventNo,false);
                    log.error("pay entitlement outbox exhausted retries eventNo={} type={} failureType={}",
                            eventNo,row.getEventType(),failure.getClass().getSimpleName());
                }
                return;
            }
            long delaySeconds=Math.min(300,1L<<Math.min(attempts,8));
            outbox.defer(row.getId(),TENANT,now,now.plusSeconds(delaySeconds),row.getVersion());
            retryTimer.schedule(() -> relay(eventNo),delaySeconds,TimeUnit.SECONDS);
            log.warn("pay entitlement outbox bridge failed eventNo={} type={} failureType={}",
                    eventNo,row.getEventType(),failure.getClass().getSimpleName());
            return;
        }
        recoverDelivery(eventNo,true);
    }
    /** Queue work off the pay after-commit thread so the platform write gets a fresh session. */
    public void submit(String eventNo) { relayWorker.execute(() -> relay(eventNo)); }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        relayPendingSafely();
        retryTimer.scheduleWithFixedDelay(this::relayPendingSafely,5,5,TimeUnit.SECONDS);
    }
    public int relayPending() {
        List<PayEntOutbox> rows=outbox.selectPending(TENANT,LocalDateTime.now(ZoneOffset.UTC),BATCH_SIZE);
        rows.forEach(row -> submit(row.getEventNo()));
        return rows.size();
    }
    private void relayPendingSafely() {
        try { relayPending(); recoverDeliveryFailures(); }
        catch(RuntimeException failure) {
            log.warn("pay entitlement recovery scan failed failureType={}",failure.getClass().getSimpleName());
        }
    }
    /** Bounded keyset rotation keeps corrupt rows from starving later failures and repairs crash gaps. */
    public synchronized int recoverDeliveryFailures() {
        if(delivery==null) return 0;
        var rows=outbox.selectDeliveryRecovery(TENANT,recoveryCursor,BATCH_SIZE);
        if(rows.isEmpty()) { recoveryCursor=0; return 0; }
        for(var row:rows) {
            recoverDelivery(row.getEventNo(),"SENT".equals(row.getStatus()));
            recoveryCursor=row.getId();
        }
        return rows.size();
    }
    private void recoverDelivery(String eventNo,boolean sent) {
        if(delivery==null) return;
        try {
            if(sent) delivery.resolveSent(eventNo); else delivery.captureDead(eventNo);
        } catch(RuntimeException failure) {
            log.warn("pay entitlement delivery reconciliation failed eventNo={} failureType={}",
                    eventNo,failure.getClass().getSimpleName());
        }
    }
    @PreDestroy
    public void shutdown() {
        retryTimer.shutdownNow(); relayWorker.shutdownNow();
    }
}
