package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.QueueContracts.ChangeReq;
import ai.neargo.shop.food.dto.QueueContracts.IssueReq;
import ai.neargo.shop.food.dto.QueueContracts.Query;
import ai.neargo.shop.food.dto.QueueContracts.QueueVO;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbQueueTicket;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.QueueTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.QueueSeatingPolicy;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.lifecycle.QueueNotificationLifecycle;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.DiningQueueChanged;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Reception queue with original-key seating recovery; CheckService exclusively owns occupancy. */
public class QueueServiceImpl implements QueueService {
    private QueueNotificationLifecycle notifications;

    @Autowired(required = false)
    public void setNotifications(QueueNotificationLifecycle notices) {
        notifications = notices;
    }

    private static final Set<String> STATES =
            Set.of("WAITING", "CALLED", "SEATING", "SEATED", "CANCELLED", "NO_SHOW");
    private final QueueTicketMapper tickets;
    private final StorePolicyMapper policyRows;
    private final ActionLogMapper actions;
    private final StoreOperationService policies;
    private final CheckService checks;
    private final StoreCardQueryPort stores;
    private final IdempotencyService idem;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public QueueServiceImpl(
            QueueTicketMapper tickets,
            StorePolicyMapper policyRows,
            ActionLogMapper actions,
            StoreOperationService policies,
            CheckService checks,
            StoreCardQueryPort stores,
            IdempotencyService idem,
            OutboxPublisher outbox,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.tickets = tickets;
        this.policyRows = policyRows;
        this.actions = actions;
        this.policies = policies;
        this.checks = checks;
        this.stores = stores;
        this.idem = idem;
        this.outbox = outbox;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public QueueVO issue(String entity, String store, String actor, IssueReq request, String key) {
        outside();
        boolean active = scope(entity, store, actor, false);
        replayKey(key);
        if (request == null
                || request.partySize() == null
                || request.partySize() < 1
                || request.partySize() > 1000
                || request.contactRef() != null && !valid(request.contactRef(), 64)) throw bad();
        String requestKey =
                FoodCommandFingerprint.hash(
                        FoodCommandFingerprint.scope("QUEUE_ISSUE", entity, store), actor, key);
        String requestHash = FoodCommandFingerprint.hash(json.writeValueAsString(request));
        // Date is chosen by the existing policy; original successful requests keep their original
        // date on replay.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var policy = policies.getPolicy(entity, store, new GetPolicyQuery(now));
        LocalDate date = ServiceCalendarPolicy.businessDate(now, policy);
        return transaction(
                () -> {
                    anchor(entity, store);
                    var prior =
                            tickets.selectList(
                                    Wrappers.<FnbQueueTicket>lambdaQuery()
                                            .eq(FnbQueueTicket::getEntityNo, entity)
                                            .eq(FnbQueueTicket::getStoreNo, store)
                                            .eq(FnbQueueTicket::getRequestKey, requestKey));
                    if (!prior.isEmpty()) {
                        if (!requestHash.equals(prior.getFirst().getRequestHash()))
                            throw conflict();
                        return view(prior.getFirst());
                    }
                    if (!active) throw BizException.of(ErrorCode.FORBIDDEN);
                    var row = new FnbQueueTicket();
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setTicketNo(BizKey.next("FQUE"));
                    row.setBusinessDate(date);
                    row.setPartySize(request.partySize());
                    row.setContactRef(request.contactRef());
                    row.setStatus("WAITING");
                    row.setRequestKey(requestKey);
                    row.setRequestHash(requestHash);
                    row.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
                    row.setCreatedBy(actor);
                    row.setUpdatedBy(actor);
                    row.setVersion(0L);
                    tickets.insert(row);
                    changed(row, actor, "QUEUE_ISSUE", null, key);
                    return view(row);
                });
    }

    @Override
    public PageData<QueueVO> list(String entity, String store, String actor, Query query) {
        outside();
        scope(entity, store, actor, false);
        if (query == null) throw bad();
        date(query.businessDate());
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 20 : query.size();
        if (page < 1
                || size < 1
                || size > 100
                || query.status() != null && !STATES.contains(query.status())) throw bad();
        return transaction(
                () -> {
                    var result =
                            tickets.selectPage(
                                    new Page<FnbQueueTicket>(page, size),
                                    Wrappers.<FnbQueueTicket>lambdaQuery()
                                            .eq(FnbQueueTicket::getEntityNo, entity)
                                            .eq(FnbQueueTicket::getStoreNo, store)
                                            .eq(
                                                    FnbQueueTicket::getBusinessDate,
                                                    query.businessDate())
                                            .eq(
                                                    query.status() != null,
                                                    FnbQueueTicket::getStatus,
                                                    query.status())
                                            .orderByAsc(FnbQueueTicket::getCreatedAt)
                                            .orderByAsc(FnbQueueTicket::getId));
                    return PageData.of(
                            result.getRecords().stream().map(this::view).toList(),
                            result.getTotal(),
                            page,
                            size);
                });
    }

    @Override
    public QueueVO get(String entity, String store, String actor, String number) {
        outside();
        scope(entity, store, actor, false);
        key(number);
        return transaction(() -> view(find(entity, store, number, false)));
    }

    @Override
    public QueueVO callNext(String entity, String store, String actor, LocalDate date, String key) {
        outside();
        boolean active = scope(entity, store, actor, false);
        date(date);
        replayKey(key);
        return command(
                entity,
                store,
                actor,
                key,
                "QUEUE_CALL_NEXT",
                date.toString(),
                () -> {
                    anchor(entity, store);
                    String originalHash = FoodCommandFingerprint.hash(date.toString());
                    var receipt =
                            actionReceipt(
                                    entity, store, actor, "QUEUE_CALL_NEXT", key, originalHash);
                    if (receipt != null)
                        return view(find(entity, store, receipt.getObjectNo(), false));
                    if (!active) throw BizException.of(ErrorCode.FORBIDDEN);
                    var next =
                            tickets.selectList(
                                    Wrappers.<FnbQueueTicket>lambdaQuery()
                                            .eq(FnbQueueTicket::getEntityNo, entity)
                                            .eq(FnbQueueTicket::getStoreNo, store)
                                            .eq(FnbQueueTicket::getBusinessDate, date)
                                            .eq(FnbQueueTicket::getStatus, "WAITING")
                                            .orderByAsc(FnbQueueTicket::getCreatedAt)
                                            .orderByAsc(FnbQueueTicket::getId)
                                            .last("LIMIT 1 FOR UPDATE"));
                    if (next.isEmpty()) throw missing();
                    var row = next.getFirst();
                    row.setStatus("CALLED");
                    row.setCalledAt(now());
                    update(row, actor);
                    changed(row, actor, "QUEUE_CALL_NEXT", null, key, originalHash);
                    return view(row);
                });
    }

    @Override
    public QueueVO change(
            String entity,
            String store,
            String actor,
            String number,
            String action,
            ChangeReq request,
            String key) {
        outside();
        scope(entity, store, actor, false);
        key(number);
        replayKey(key);
        if (request == null
                || request.version() == null
                || request.version() < 0
                || action == null
                || !Set.of("CALL", "CANCEL", "NO_SHOW").contains(action)
                || !"CALL".equals(action) && !valid(request.reason(), 255)) throw bad();
        String hash = FoodCommandFingerprint.hash(number, action, json.writeValueAsString(request));
        return command(
                entity,
                store,
                actor,
                key,
                "QUEUE_" + action,
                hash,
                () -> {
                    var receipt = actionReceipt(entity, store, actor, "QUEUE_" + action, key, hash);
                    if (receipt != null) {
                        if (!number.equals(receipt.getObjectNo())) throw conflict();
                        return view(find(entity, store, number, false));
                    }
                    var row = find(entity, store, number, true);
                    String desired =
                            switch (action) {
                                case "CALL" -> "CALLED";
                                case "CANCEL" -> "CANCELLED";
                                default -> "NO_SHOW";
                            };
                    if (desired.equals(row.getStatus())) return view(row);
                    if (!Objects.equals(request.version(), row.getVersion())) throw conflict();
                    boolean allowed =
                            switch (action) {
                                case "CALL" -> "WAITING".equals(row.getStatus());
                                case "CANCEL" ->
                                        Set.of("WAITING", "CALLED").contains(row.getStatus());
                                default ->
                                        "CALLED".equals(row.getStatus())
                                                && row.getCalledAt() != null;
                            };
                    if (!allowed) throw conflict();
                    row.setStatus(desired);
                    if ("CALL".equals(action)) row.setCalledAt(now());
                    update(row, actor);
                    changed(row, actor, "QUEUE_" + action, request.reason(), key, hash);
                    return view(row);
                });
    }

    @Override
    public QueueVO seat(
            String entity, String store, String actor, String number, SeatQueueReq request) {
        outside();
        scope(entity, store, actor, false);
        key(number);
        if (request == null || request.version() == null || request.version() < 0) throw bad();
        var row =
                transaction(
                        () -> {
                            var current = find(entity, store, number, true);
                            var check =
                                    QueueSeatingPolicy.normalize(
                                            request.check(), current.getPartySize());
                            String payload = json.writeValueAsString(check);
                            String hash = FoodCommandFingerprint.hash(payload);
                            if (Set.of("SEATING", "SEATED").contains(current.getStatus())) {
                                if (!hash.equals(current.getSeatRequestHash())) throw conflict();
                                return current;
                            }
                            if (!"CALLED".equals(current.getStatus())
                                    || !request.version().equals(current.getVersion()))
                                throw conflict();
                            // Queue and original check owner share the primary transaction. A table
                            // conflict rolls
                            // back both commands, leaving CALLED available for a different
                            // table/version.
                            var opened =
                                    checks.openQueuedCheck(
                                            entity, store, actor, check, seatKey(number));
                            var actual = opened == null ? null : opened.check();
                            if (actual == null
                                    || !store.equals(actual.storeNo())
                                    || actual.partySize() != current.getPartySize())
                                throw conflict();
                            current.setSeatRequest(payload);
                            current.setSeatRequestHash(hash);
                            current.setSeatActorNo(actor);
                            current.setStatus("SEATED");
                            current.setCheckNo(actual.checkNo());
                            update(current, actor);
                            changed(current, actor, "QUEUE_SEATED", null, seatKey(number));
                            return current;
                        });
        return resume(entity, store, actor, row);
    }

    @Override
    public QueueVO recoverSeating(String entity, String store, String actor, String number) {
        outside();
        scope(entity, store, actor, false);
        key(number);
        var row = transaction(() -> find(entity, store, number, false));
        return resume(entity, store, actor, row);
    }

    private QueueVO resume(String entity, String store, String actor, FnbQueueTicket row) {
        if ("SEATED".equals(row.getStatus())) return view(row);
        if (!"SEATING".equals(row.getStatus())
                || row.getSeatRequest() == null
                || row.getSeatActorNo() == null) throw conflict();
        var request = json.readValue(row.getSeatRequest(), OpenCheckReq.class);
        if (!FoodCommandFingerprint.hash(json.writeValueAsString(request))
                .equals(row.getSeatRequestHash())) throw conflict();
        // This immutable owner audit survives shared idempotency expiry and a crash before the
        // queue link commit.
        String ownerRequest =
                FoodCommandFingerprint.hash(
                        FoodCommandFingerprint.scope("OPEN_CHECK", entity, store),
                        seatKey(row.getTicketNo()));
        var original =
                transaction(
                        () ->
                                actions.selectList(
                                        Wrappers.<FnbActionLog>lambdaQuery()
                                                .eq(FnbActionLog::getEntityNo, entity)
                                                .eq(FnbActionLog::getStoreNo, store)
                                                .eq(FnbActionLog::getObjectType, "CHECK")
                                                .eq(FnbActionLog::getAction, "OPEN_CHECK")
                                                .eq(FnbActionLog::getRequestNo, ownerRequest)));
        if (original.size() > 1
                || !original.isEmpty()
                        && !row.getSeatActorNo().equals(original.getFirst().getOperatorNo()))
            throw conflict();
        final CheckVO check;
        try {
            check =
                    original.isEmpty()
                            ? checks.openCheck(
                                            entity,
                                            store,
                                            row.getSeatActorNo(),
                                            request,
                                            seatKey(row.getTicketNo()))
                                    .check()
                            : checks.getCheck(entity, store, original.getFirst().getObjectNo())
                                    .check();
        } catch (RuntimeException unresolved) {
            // Never mark occupancy or allow a fresh seating request while the original owner's
            // outcome is unresolved.
            return transaction(() -> view(find(entity, store, row.getTicketNo(), false)));
        }
        if (check == null
                || !store.equals(check.storeNo())
                || check.partySize() != row.getPartySize()) throw conflict();
        return transaction(
                () -> {
                    var current = find(entity, store, row.getTicketNo(), true);
                    if ("SEATED".equals(current.getStatus())) {
                        if (!Objects.equals(check.checkNo(), current.getCheckNo()))
                            throw conflict();
                        return view(current);
                    }
                    if (!"SEATING".equals(current.getStatus())
                            || !row.getSeatRequestHash().equals(current.getSeatRequestHash()))
                        throw conflict();
                    current.setStatus("SEATED");
                    current.setCheckNo(check.checkNo());
                    update(current, actor);
                    changed(current, actor, "QUEUE_SEATED", null, seatKey(row.getTicketNo()));
                    return view(current);
                });
    }

    /**
     * Oldest immutable policy is a stable store anchor across new effective policy publications.
     */
    private void anchor(String entity, String store) {
        var rows =
                policyRows.selectList(
                        Wrappers.<FnbStorePolicy>lambdaQuery()
                                .eq(FnbStorePolicy::getEntityNo, entity)
                                .eq(FnbStorePolicy::getStoreNo, store)
                                .orderByAsc(FnbStorePolicy::getEffectiveFrom)
                                .orderByAsc(FnbStorePolicy::getPolicyNo)
                                .last("LIMIT 1 FOR UPDATE"));
        if (rows.isEmpty()) throw missing();
    }

    private FnbQueueTicket find(String entity, String store, String no, boolean lock) {
        var rows =
                tickets.selectList(
                        Wrappers.<FnbQueueTicket>lambdaQuery()
                                .eq(FnbQueueTicket::getEntityNo, entity)
                                .eq(FnbQueueTicket::getStoreNo, store)
                                .eq(FnbQueueTicket::getTicketNo, no)
                                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private void update(FnbQueueTicket row, String actor) {
        if (row.getVersion() == null || row.getVersion() == Long.MAX_VALUE) throw conflict();
        row.setUpdatedBy(actor);
        if (tickets.updateById(row) != 1) throw conflict();
    }

    private void changed(
            FnbQueueTicket row, String actor, String action, String reason, String key) {
        changed(row, actor, action, reason, key, null);
    }

    private FnbActionLog actionReceipt(
            String entity, String store, String actor, String action, String key, String hash) {
        var rows =
                actions.selectList(
                        Wrappers.<FnbActionLog>lambdaQuery()
                                .eq(FnbActionLog::getEntityNo, entity)
                                .eq(FnbActionLog::getStoreNo, store)
                                .eq(FnbActionLog::getObjectType, "QUEUE")
                                .eq(FnbActionLog::getAction, action)
                                .eq(
                                        FnbActionLog::getRequestNo,
                                        FoodCommandFingerprint.hash(
                                                FoodCommandFingerprint.scope(action, entity, store),
                                                key)));
        if (rows.size() > 1) throw conflict();
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!actor.equals(row.getOperatorNo()) || !Objects.equals(hash, row.getBeforeRef()))
            throw conflict();
        return row;
    }

    private void changed(
            FnbQueueTicket row,
            String actor,
            String action,
            String reason,
            String key,
            String requestHash) {
        var audit = new FnbActionLog();
        audit.setEntityNo(row.getEntityNo());
        audit.setStoreNo(row.getStoreNo());
        audit.setActionNo(BizKey.next("FQAC"));
        audit.setObjectType("QUEUE");
        audit.setObjectNo(row.getTicketNo());
        audit.setAction(action);
        audit.setOperatorNo(actor);
        audit.setReason(reason);
        audit.setRequestNo(
                FoodCommandFingerprint.hash(
                        FoodCommandFingerprint.scope(action, row.getEntityNo(), row.getStoreNo()),
                        key));
        audit.setBeforeRef(requestHash);
        audit.setAfterRef(
                FoodCommandFingerprint.hash(
                        row.getTicketNo(), row.getStatus(), row.getVersion().toString()));
        audit.setCreatedBy(actor);
        audit.setUpdatedBy(actor);
        actions.insert(audit);
        if (notifications != null && "CALLED".equals(row.getStatus()))
            notifications.called(
                    row.getEntityNo(),
                    row.getStoreNo(),
                    row.getTicketNo(),
                    row.getVersion(),
                    row.getContactRef(),
                    actor);
        outbox.publish(
                new DiningQueueChanged(
                        row.getEntityNo(),
                        row.getStoreNo(),
                        row.getTicketNo(),
                        row.getBusinessDate(),
                        row.getStatus(),
                        row.getPartySize(),
                        row.getCheckNo(),
                        actor,
                        row.getVersion()),
                new PublishOptions("FOOD_QUEUE:" + row.getTicketNo(), 1));
    }

    private QueueVO view(FnbQueueTicket row) {
        return new QueueVO(
                row.getTicketNo(),
                row.getBusinessDate(),
                row.getPartySize(),
                row.getStatus(),
                row.getCalledAt(),
                row.getCheckNo(),
                row.getVersion(),
                "OUTBOX_RECORDED");
    }

    private QueueVO command(
            String entity,
            String store,
            String actor,
            String key,
            String action,
            String payload,
            Supplier<QueueVO> work) {
        String scope = FoodCommandFingerprint.scope(action, entity, store);
        return transaction(
                () ->
                        idem.execute(
                                key,
                                scope,
                                actor,
                                FoodCommandFingerprint.hash(payload),
                                QueueVO.class,
                                work));
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return DataScopeContext.executeWithoutScope(() -> tx.execute(status -> work.get()));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw conflict();
        }
    }

    private boolean scope(String entity, String store, String actor, boolean active) {
        key(entity);
        key(store);
        key(actor);
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !entity.equals(card.entityNo()) || active && !card.active())
            throw BizException.of(ErrorCode.FORBIDDEN);
        return card.active();
    }

    private void key(String value) {
        if (!valid(value, 32)) throw bad();
    }

    private void replayKey(String value) {
        if (!valid(value, 128)) throw bad();
    }

    private boolean valid(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.length() <= max
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private void date(LocalDate date) {
        if (date == null || date.getYear() < 1000 || date.getYear() > 9999) throw bad();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Queue commands require their own transactions");
    }

    public static String seatKey(String number) {
        return "QUEUE_SEAT:" + number;
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static BizException missing() {
        return BizException.of(ErrorCode.NOT_FOUND);
    }
}
