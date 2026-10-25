package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.service.QueueNotificationLifecycle;
import ai.neargo.shop.food.service.QueueNotificationService;
import ai.neargo.shop.food.spi.QueueNotificationPort;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Durable provider receipts distinguish a call intention from actual notification delivery. */
public class QueueNotificationServiceImpl
        implements QueueNotificationService, QueueNotificationLifecycle {
    private final FoodBusinessOperationsMapper mapper;
    private final TransactionTemplate tx;
    private final Supplier<QueueNotificationPort> provider;
    private final Clock clock;

    public QueueNotificationServiceImpl(
            FoodBusinessOperationsMapper mapper,
            PlatformTransactionManager manager,
            Supplier<QueueNotificationPort> provider,
            Clock clock) {
        this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        this.provider = provider;
        this.clock = clock;
    }

    @Override
    public void called(
            String e,
            String store,
            String ticket,
            long ticketVersion,
            String contact,
            String actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Queue notice joins queue transaction");
        if (contact == null || contact.isBlank()) return;
        mapper.recordQueueNotice(
                e, store, BizKey.next("FQNT"), ticket, ticketVersion, contact, actor);
    }

    @Override
    public List<NoticeVO> list(String e, String store, String ticket) {
        return mapper.queueNotices(e, store, ticket).stream().map(this::view).toList();
    }

    @Override
    public NoticeVO recover(String e, String store, String actor, String no, String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider calls must not hold the queue transaction");
        var rows = mapper.queueNotice(e, store, no);
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        var row = rows.getFirst();
        if (Set.of("DELIVERED", "FAILED").contains(row.get("status"))) return view(row);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        long version = row.number("version");
        if (!Boolean.TRUE.equals(
                tx.execute(
                        t ->
                                mapper.claimQueueNotice(
                                                e, store, no, version, now, now.plusMinutes(2))
                                        == 1)))
            return view(mapper.queueNotice(e, store, no).getFirst());
        String status = "UNKNOWN", receipt = null, error = null;
        var owner = provider.get();
        if (owner == null) {
            status = (String) row.get("status");
            error = "PROVIDER_UNAVAILABLE";
        } else
            try {
                var request =
                        new QueueNotificationPort.Request(
                                e,
                                store,
                                row.text("ticket_no"),
                                row.text("contact_ref"),
                                "QUEUE_NOTICE:" + no);
                var actual = owner.lookup(request);
                if (actual != null && !request.originalKey().equals(actual.originalKey()))
                    throw new IllegalStateException("Foreign notification receipt");
                if (actual != null && "ABSENT".equals(actual.status()))
                    actual = owner.send(request);
                if (actual == null
                        || !request.originalKey().equals(actual.originalKey())
                        || !Set.of("ACCEPTED", "DELIVERED", "FAILED", "UNKNOWN")
                                .contains(actual.status()))
                    throw new IllegalStateException("Missing original provider receipt");
                status = actual.status();
                receipt = actual.providerRef();
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName();
            }
        String finalStatus = status, finalReceipt = receipt, finalError = error;
        tx.execute(
                t ->
                        mapper.finishQueueNotice(
                                e,
                                store,
                                no,
                                version + 1,
                                finalStatus,
                                finalReceipt,
                                finalError,
                                now.plusMinutes(2)));
        return view(mapper.queueNotice(e, store, no).getFirst());
    }

    public int runDue(int limit) {
        if (limit < 1 || limit > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        int attempted = 0;
        for (var row :
                mapper.dueQueueNotices(
                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), limit)) {
            recover(
                    row.text("entity_no"),
                    row.text("store_no"),
                    "SYSTEM",
                    row.text("notice_no"),
                    "QUEUE_NOTICE_RECOVER:" + row.text("notice_no"));
            attempted++;
        }
        return attempted;
    }

    private NoticeVO view(FoodBusinessOperationRow row) {
        return new NoticeVO(
                row.text("notice_no"),
                row.text("ticket_no"),
                row.number("ticket_version"),
                row.text("status"),
                row.text("provider_ref"),
                row.number("version"));
    }
}
