package ai.neargo.shop.pay.points.event.impl;

import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Lease;
import ai.neargo.shop.pay.points.event.PointsEventWorker;
import ai.neargo.shop.pay.points.event.PointsSourceFactHandler;
import ai.neargo.shop.pay.points.lifecycle.RefundClosureService;
import ai.neargo.shop.pay.points.lifecycle.ExpiryReminderService;
import ai.neargo.shop.spi.notify.UserInboxPort;
import ai.neargo.shop.spi.notify.UserInboxPort.PointsExpiry;
import ai.neargo.shop.pay.points.refund.PointsRefundZeroCashService;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.RefundConfirmationFact;
import ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand;
import ai.neargo.shop.spi.points.PointsFactPort.RefundClosureFact;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Every handler is idempotent; the lease acknowledgement is deliberately a separate transaction. */
@Service
public class PointsEventWorkerImpl implements PointsEventWorker {
    private final PointsEventQueue queue;
    private final PointsSourceFactHandler source;
    private final RefundClosureService closures;
    private final PointsRefundZeroCashService zero;
    private final ExpiryReminderService reminders;
    private final ObjectProvider<UserInboxPort> inboxes;
    private final ObjectMapper json=new ObjectMapper();
    public PointsEventWorkerImpl(PointsEventQueue queue,PointsSourceFactHandler source,
                                RefundClosureService closures,PointsRefundZeroCashService zero,
            ExpiryReminderService reminders,ObjectProvider<UserInboxPort> inboxes) {
        this.queue=queue; this.source=source; this.closures=closures; this.zero=zero; this.reminders=reminders; this.inboxes=inboxes;
    }
    @Override public int drain(int limit,long now) { return drain(null,limit,now); }
    @Override public int drain(String type,int limit,long now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("POINTS_DELIVERY_REQUIRES_NO_TRANSACTION");
        }
        if (limit<1 || limit>200 || now<0) { throw new IllegalArgumentException("POINTS_BATCH_INVALID"); }
        int handled=0;
        for (int i=0;i<limit;i++) {
            var lease=queue.claim(type,now);
            if (lease==null) { break; }
            try { dispatch(lease,now); }
            catch (RuntimeException failure) {
                String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                boolean permanent=reason.contains("CONFLICT") || reason.contains("MISMATCH")
                        || reason.contains("INVALID") || reason.contains("TYPED_PROOF_REQUIRED") || reason.contains("AFTER_PAID")
                        || reason.contains("AFTER_RELEASE");
                queue.fail(lease,now,permanent,reason.length()>120?reason.substring(0,120):reason);
                continue;
            }
            queue.complete(lease); handled++;
        }
        return handled;
    }
    private void dispatch(Lease lease,long now) {
        String body=lease.message().payload();
        switch (lease.message().kind()) {
            case SOURCE_FACT -> source.apply(json.readValue(body,LifecycleFact.class));
            case EARN_GRANT -> source.earn(json.readValue(body,GrantCommand.class));
            case REFUND_CONFIRMATION -> source.confirm(json.readValue(body,RefundConfirmationFact.class));
            case REFUND_CLOSURE -> closures.close(json.readValue(body,RefundClosureFact.class));
            case ZERO_CASH -> zero.accept(json.readValue(body,ZeroCashCompletionFact.class));
            case EXPIRY_REMINDER -> {
                var notice=json.readValue(body,ExpiryReminderService.Reminder.class);
                if (reminders.current(notice,now)) {
                    var inbox=inboxes.getIfAvailable();
                    if (inbox==null) { throw new IllegalStateException("POINTS_NOTIFICATION_UNAVAILABLE"); }
                    inbox.pointsExpiring(new PointsExpiry(notice.userNo(),notice.points(),notice.expireAt(),
                            notice.dedupKey()));
                }
            }
        }
    }
}
