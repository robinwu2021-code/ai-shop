package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.ChangeReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbReservation;
import ai.neargo.shop.food.entity.FnbReservationTable;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.ReservationTableMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.TableResourceLocks;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.ReservationChanged;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Bounded local FOOD allocations share the existing real table arbiter; external holds stay outside
 * this authority.
 */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class ReservationServiceImpl implements ReservationService {
    private ServiceFormServiceImpl serviceForms;

    @Autowired(required = false)
    public void setServiceForms(ServiceFormServiceImpl forms) {
        serviceForms = forms;
    }

    private ReservationPreorderLifecycle preorderLifecycle;

    @Autowired(required = false)
    public void setPreorderLifecycle(ReservationPreorderLifecycle lifecycle) {
        this.preorderLifecycle = lifecycle;
    }

    private final ReservationMapper reservations;
    private final ReservationTableMapper memberships;
    private final TableOccupancyMapper occupancies;
    private final ActionLogMapper actions;
    private final CheckService checks;
    private final StoreOperationService policies;
    private final IdempotencyService idem;
    private final ObjectMapper json;
    private final StoreCardQueryPort stores;
    private final OutboxPublisher outbox;
    private final TableResourceLocks locks;
    private final TransactionTemplate tx;

    public ReservationServiceImpl(
            ReservationMapper reservations,
            ReservationTableMapper memberships,
            TableAreaMapper areas,
            TableMapper tables,
            TableOccupancyMapper occupancies,
            ActionLogMapper actions,
            CheckService checks,
            StoreOperationService policies,
            IdempotencyService idem,
            ObjectMapper json,
            StoreCardQueryPort stores,
            @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.reservations = reservations;
        this.memberships = memberships;
        this.occupancies = occupancies;
        this.actions = actions;
        this.checks = checks;
        this.policies = policies;
        this.idem = idem;
        this.json = json;
        this.stores = stores;
        this.outbox = outbox;
        locks = new TableResourceLocks(areas, tables);
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public DetailVO get(String entity, String store, String number) {
        scope(entity, store);
        id(number);
        return detail(find(entity, store, number, false));
    }

    @Override
    public DetailVO create(String entity, String store, String actor, CreateReq req, String key) {
        validate(req);
        return write(
                "RESERVATION_CREATE",
                entity,
                store,
                actor,
                key,
                req,
                () -> {
                    String requestKey =
                            FoodCommandFingerprint.hash(
                                    FoodCommandFingerprint.scope(
                                            "RESERVATION_CREATE", entity, store),
                                    actor,
                                    key);
                    String requestHash = FoodCommandFingerprint.hash(json.writeValueAsString(req));
                    var previous =
                            reservations.selectList(
                                    Wrappers.<FnbReservation>lambdaQuery()
                                            .eq(FnbReservation::getEntityNo, entity)
                                            .eq(FnbReservation::getStoreNo, store)
                                            .eq(FnbReservation::getRequestKey, requestKey));
                    if (!previous.isEmpty()) {
                        if (!requestHash.equals(previous.getFirst().getRequestHash())) conflict();
                        return detail(previous.getFirst());
                    }
                    future(req);
                    allocate(entity, store, req, null, List.of());
                    var row = new FnbReservation();
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setCreatedBy(actor);
                    row.setUpdatedBy(actor);
                    row.setRequestKey(requestKey);
                    row.setRequestHash(requestHash);
                    row.setReservationNo(BizKey.next("FRSV"));
                    row.setStatus("CONFIRMED");
                    row.setVersion(0L);
                    apply(row, req);
                    reservations.insert(row);
                    replace(row, req.tables(), actor);
                    audit(row, actor, "RESERVATION_CREATE", null, key);
                    return detail(row);
                });
    }

    @Override
    public DetailVO change(
            String entity, String store, String actor, String number, ChangeReq req, String key) {
        id(number);
        if (req == null) bad();
        version(req.version());
        validate(req.appointment());
        text(req.reason(), 255);
        return write(
                "RESERVATION_CHANGE",
                entity,
                store,
                actor,
                key,
                List.of(number, req),
                () -> {
                    var row = mutable(entity, store, number, req.version());
                    future(req.appointment());
                    allocate(entity, store, req.appointment(), number, tableNos(row));
                    apply(row, req.appointment());
                    update(row, actor);
                    replace(row, req.appointment().tables(), actor);
                    audit(row, actor, "RESERVATION_CHANGE", req.reason(), key);
                    return detail(row);
                });
    }

    @Override
    public DetailVO cancel(
            String entity,
            String store,
            String actor,
            String number,
            TransitionReq req,
            String key) {
        return transition(entity, store, actor, number, req, key, "CANCELLED");
    }

    @Override
    public DetailVO noShow(
            String entity,
            String store,
            String actor,
            String number,
            TransitionReq req,
            String key) {
        return transition(entity, store, actor, number, req, key, "NO_SHOW");
    }

    private DetailVO transition(
            String entity,
            String store,
            String actor,
            String number,
            TransitionReq req,
            String key,
            String target) {
        id(number);
        if (req == null) bad();
        version(req.version());
        text(req.reason(), 255);
        return write(
                "RESERVATION_" + target,
                entity,
                store,
                actor,
                key,
                List.of(number, req),
                () -> {
                    var row = mutable(entity, store, number, req.version());
                    locks.lock(entity, store, tableNos(row), null, false);
                    if ("NO_SHOW".equals(target) && now().isBefore(row.getEndAt())) conflict();
                    if (preorderLifecycle != null)
                        preorderLifecycle.cancelled(entity, store, number, actor, req.reason());
                    if (serviceForms != null)
                        serviceForms.reservationTransition(entity, store, number, target, actor);
                    row.setStatus(target);
                    update(row, actor);
                    audit(row, actor, "RESERVATION_" + target, req.reason(), key);
                    return detail(row);
                });
    }

    @Override
    public DetailVO arrive(
            String entity, String store, String actor, String number, ArriveReq req, String key) {
        id(number);
        if (req == null) bad();
        version(req.version());
        var supplied = versions(req.tables());
        return write(
                "RESERVATION_ARRIVE",
                entity,
                store,
                actor,
                key,
                List.of(number, req),
                () -> {
                    var row = mutable(entity, store, number, req.version());
                    var assigned = tableNos(row);
                    if (!supplied.keySet().equals(Set.copyOf(assigned))) bad();
                    var at = now();
                    if (at.isBefore(row.getStartAt()) || !at.isBefore(row.getEndAt())) conflict();
                    locks.lock(entity, store, assigned, supplied, true);
                    // The original appointment interval remains the admission boundary for this
                    // bounded handoff.
                    for (String table : assigned) {
                        if (memberships.overlaps(
                                        entity,
                                        store,
                                        table,
                                        row.getStartAt(),
                                        row.getEndAt(),
                                        number)
                                > 0) conflict();
                    }
                    var policy =
                            policies.getPolicy(
                                    entity,
                                    store,
                                    new GetPolicyQuery(at.toInstant(ZoneOffset.UTC)));
                    if (policy == null
                            || !("PREPAY".equals(policy.payPolicy())
                                    || "POSTPAY".equals(policy.payPolicy()))) conflict();
                    row.setStatus("ARRIVED");
                    update(row, actor);
                    var opened =
                            checks.openReservedCheck(
                                    entity,
                                    store,
                                    actor,
                                    new OpenCheckReq(
                                            assigned,
                                            row.getPartySize(),
                                            policy.payPolicy(),
                                            null,
                                            req.tables(),
                                            "EXCLUSIVE",
                                            List.of(),
                                            null),
                                    "RSV-" + number,
                                    row.getEndAt().toInstant(ZoneOffset.UTC));
                    var check = opened == null ? null : opened.check();
                    if (check == null
                            || !store.equals(check.storeNo())
                            || check.partySize() != row.getPartySize()
                            || !policy.payPolicy().equals(check.payMode())) conflict();
                    row.setCheckNo(check.checkNo());
                    update(row, actor);
                    audit(row, actor, "RESERVATION_ARRIVE", null, key);
                    return detail(row);
                });
    }

    private DetailVO write(
            String operation,
            String entity,
            String store,
            String actor,
            String key,
            Object input,
            Supplier<DetailVO> body) {
        scope(entity, store);
        text(actor, 32);
        text(key, 128);
        var card = stores.cards(List.of(store)).get(store);
        boolean requiresActive =
                !Set.of("RESERVATION_CANCELLED", "RESERVATION_NO_SHOW").contains(operation);
        if (card == null || !entity.equals(card.entityNo()) || requiresActive && !card.active())
            throw BizException.of(ErrorCode.FORBIDDEN);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Reservation commands own the primary transaction");
        String hash = FoodCommandFingerprint.hash(json.writeValueAsString(input));
        try {
            return tx.execute(
                    status ->
                            idem.execute(
                                    key,
                                    FoodCommandFingerprint.scope(operation, entity, store),
                                    actor,
                                    hash,
                                    DetailVO.class,
                                    body));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private FnbReservation find(String entity, String store, String number, boolean lock) {
        var rows =
                reservations.selectList(
                        Wrappers.<FnbReservation>lambdaQuery()
                                .eq(FnbReservation::getEntityNo, entity)
                                .eq(FnbReservation::getStoreNo, store)
                                .eq(FnbReservation::getReservationNo, number)
                                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private FnbReservation mutable(String entity, String store, String number, Long version) {
        var row = find(entity, store, number, true);
        if (!version.equals(row.getVersion())) conflict();
        if (!Set.of("HELD", "CONFIRMED").contains(row.getStatus())) conflict();
        // Historical rows without a real local allocation cannot be promoted into this authority.
        if (row.getResourceHoldNo() != null
                || row.getDepositRef() != null
                || row.getBuyerNo() != null
                || tableNos(row).isEmpty()) throw BizException.of(ErrorCode.FORBIDDEN);
        return row;
    }

    private List<String> tableNos(FnbReservation row) {
        return memberships
                .selectList(
                        Wrappers.<FnbReservationTable>lambdaQuery()
                                .eq(FnbReservationTable::getEntityNo, row.getEntityNo())
                                .eq(FnbReservationTable::getStoreNo, row.getStoreNo())
                                .eq(FnbReservationTable::getReservationNo, row.getReservationNo())
                                .orderByAsc(FnbReservationTable::getTableNo))
                .stream()
                .map(FnbReservationTable::getTableNo)
                .toList();
    }

    private void allocate(
            String entity, String store, CreateReq req, String own, List<String> previous) {
        var supplied = versions(req.tables());
        var locked =
                locks.lock(
                        entity,
                        store,
                        Stream.concat(previous.stream(), supplied.keySet().stream())
                                .distinct()
                                .toList(),
                        null,
                        true);
        long capacity = 0;
        for (var table : locked) {
            if (!supplied.containsKey(table.getTableNo())) continue;
            if (!table.getVersion().equals(supplied.get(table.getTableNo()))) conflict();
            if (table.getResourceNo() != null) throw BizException.of(ErrorCode.FORBIDDEN);
            if (table.getCapacity() == null || table.getCapacity() < 1) conflict();
            // Existing open occupancy has no authoritative end time, so future availability cannot
            // be guessed.
            if (occupancies.selectCount(
                            Wrappers.<FnbTableOccupancy>lambdaQuery()
                                    .eq(FnbTableOccupancy::getEntityNo, entity)
                                    .eq(FnbTableOccupancy::getTableNo, table.getTableNo())
                                    .isNull(FnbTableOccupancy::getEndedAt))
                    > 0) conflict();
            if (memberships.overlaps(
                            entity,
                            store,
                            table.getTableNo(),
                            local(req.startAt()),
                            local(req.endAt()),
                            own)
                    > 0) conflict();
            capacity += table.getCapacity();
        }
        if (capacity < req.partySize()) conflict();
    }

    private void replace(FnbReservation row, List<TableVersionReq> selected, String actor) {
        // Membership is derived configuration; transactional outbox facts retain prior selections.
        memberships.clear(row.getEntityNo(), row.getStoreNo(), row.getReservationNo());
        for (var table : selected) {
            var membership = new FnbReservationTable();
            membership.setEntityNo(row.getEntityNo());
            membership.setStoreNo(row.getStoreNo());
            membership.setReservationNo(row.getReservationNo());
            membership.setTableNo(table.tableNo());
            membership.setVersion(0L);
            membership.setCreatedBy(actor);
            membership.setUpdatedBy(actor);
            memberships.insert(membership);
        }
    }

    private void apply(FnbReservation row, CreateReq req) {
        row.setPartySize(req.partySize());
        row.setStartAt(local(req.startAt()));
        row.setEndAt(local(req.endAt()));
        row.setContactRef(req.contactRef().trim());
    }

    private void update(FnbReservation row, String actor) {
        row.setUpdatedBy(actor);
        if (row.getVersion() == Long.MAX_VALUE || reservations.updateById(row) != 1) conflict();
    }

    private DetailVO detail(FnbReservation row) {
        return new DetailVO(
                new ReservationVO(
                        row.getReservationNo(),
                        row.getPartySize(),
                        row.getStartAt().toInstant(ZoneOffset.UTC),
                        row.getEndAt().toInstant(ZoneOffset.UTC),
                        row.getStatus(),
                        row.getDepositRef(),
                        row.getCheckNo(),
                        row.getVersion()),
                tableNos(row));
    }

    private void audit(
            FnbReservation row, String actor, String operation, String reason, String key) {
        var action = new FnbActionLog();
        action.setEntityNo(row.getEntityNo());
        action.setStoreNo(row.getStoreNo());
        action.setActionNo(BizKey.next("FACT"));
        action.setObjectType("RESERVATION");
        action.setCreatedBy(actor);
        action.setUpdatedBy(actor);
        action.setObjectNo(row.getReservationNo());
        action.setAction(operation);
        action.setOperatorNo(actor);
        action.setRequestNo(
                FoodCommandFingerprint.hash(
                        FoodCommandFingerprint.scope(
                                operation, row.getEntityNo(), row.getStoreNo()),
                        key));
        action.setReason(reason);
        action.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(detail(row))));
        actions.insert(action);
        outbox.publish(
                new ReservationChanged(
                        row.getEntityNo(),
                        row.getStoreNo(),
                        row.getReservationNo(),
                        operation,
                        row.getVersion(),
                        action.getActionNo(),
                        detail(row),
                        reason),
                new PublishOptions("FOOD_RESERVATION:" + row.getReservationNo(), 1));
    }

    private static Map<String, Long> versions(List<TableVersionReq> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 100) bad();
        var result = new HashMap<String, Long>();
        for (var row : rows) {
            if (row == null) bad();
            id(row.tableNo());
            version(row.version());
            if (result.put(row.tableNo(), row.version()) != null) bad();
        }
        return result;
    }

    private static void validate(CreateReq req) {
        if (req == null
                || req.partySize() == null
                || req.partySize() < 1
                || req.partySize() > 1000
                || !timestamp(req.startAt())
                || !timestamp(req.endAt())
                || !req.startAt().isBefore(req.endAt())
                || Duration.between(req.startAt(), req.endAt()).compareTo(Duration.ofDays(1)) > 0)
            bad();
        text(req.contactRef(), 64);
        versions(req.tables());
    }

    private static void future(CreateReq req) {
        if (!req.startAt().isAfter(Instant.now())) bad();
    }

    private static LocalDateTime now() {
        return local(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static void scope(String entity, String store) {
        if (entity == null
                || entity.isBlank()
                || entity.length() > 32
                || store == null
                || store.isBlank()
                || store.length() > 32) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private static void id(String value) {
        text(value, 32);
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) bad();
    }

    private static void version(Long value) {
        if (value == null || value < 0) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public PageData<ReservationVO> listReservations(
            String entity, String store, ListReservationsQuery query) {
        if (entity == null
                || entity.isBlank()
                || entity.length() > 32
                || store == null
                || store.isBlank()
                || store.length() > 32
                || query == null
                || !timestamp(query.from())
                || !timestamp(query.to())
                || !query.from().isBefore(query.to())) throw BizException.of(ErrorCode.BAD_REQUEST);
        int page = query.page() == null ? 1 : query.page();
        int size = query.size() == null ? 20 : query.size();
        if (page < 1
                || size < 1
                || size > 100
                || query.status() != null
                        && !Set.of("HELD", "CONFIRMED", "ARRIVED", "CANCELLED", "NO_SHOW")
                                .contains(query.status()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var filter =
                Wrappers.<FnbReservation>lambdaQuery()
                        .eq(FnbReservation::getEntityNo, entity)
                        .eq(FnbReservation::getStoreNo, store)
                        .lt(FnbReservation::getStartAt, local(query.to()))
                        .gt(FnbReservation::getEndAt, local(query.from()))
                        .eq(query.status() != null, FnbReservation::getStatus, query.status())
                        .orderByAsc(FnbReservation::getStartAt)
                        .orderByAsc(FnbReservation::getReservationNo);
        var result = reservations.selectPage(new Page<FnbReservation>(page, size), filter);
        return PageData.of(
                result.getRecords().stream()
                        .map(
                                row ->
                                        new ReservationVO(
                                                row.getReservationNo(),
                                                row.getPartySize(),
                                                row.getStartAt().toInstant(ZoneOffset.UTC),
                                                row.getEndAt().toInstant(ZoneOffset.UTC),
                                                row.getStatus(),
                                                row.getDepositRef(),
                                                row.getCheckNo(),
                                                row.getVersion()))
                        .toList(),
                result.getTotal(),
                page,
                size);
    }

    private static boolean timestamp(Instant at) {
        return at != null
                && at.getNano() % 1_000_000 == 0
                && !at.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && !at.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"));
    }

    private static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
