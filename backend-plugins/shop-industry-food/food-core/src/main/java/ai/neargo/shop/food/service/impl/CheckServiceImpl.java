package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.ListCheckActionsQuery;
import ai.neargo.shop.food.dto.ListChecksQuery;
import ai.neargo.shop.food.dto.OccupancyVO;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.SeatReq;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.dto.TransferCheckReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbSeat;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import ai.neargo.shop.food.entity.FnbTableOperation;
import ai.neargo.shop.food.mapper.FoodIndustryOperationsMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper;
import ai.neargo.shop.food.mapper.ReservationTableMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.policy.TableOccupancyPolicy;
import ai.neargo.shop.food.policy.TableResourceLocks;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.support.CommandBusinessKey;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.spi.food.CheckChanged;

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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Atomic local reception; funded/unknown checks remain closed to edits until their authority
 * contracts exist.
 */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class CheckServiceImpl implements CheckService {
    private CheckBusinessGuardService businessGuard;

    @Autowired(required = false)
    public void setBusinessGuard(CheckBusinessGuardService guard) {
        this.businessGuard = guard;
    }

    private final CheckMapper checks;
    /** 堂食权益在行业库：释放账单前单独数一次，不再和交易事实联在一条 SQL 里。 */
    private final FoodIndustryOperationsMapper industry;
    /** 桌台、占台在行业库（ADR-068 修订二）：开台先在这里占桌，再到交易库建账单。 */
    private final TransactionTemplate industryTx;
    private final TableMapper tables;
    private final TableOccupancyMapper occupancies;
    private final SeatMapper seats;
    private final TableOperationMapper operations;
    private final GuestSessionMapper sessions;
    private final ActionLogMapper actions;
    private final StoreOperationService policies;
    private final RequestIdempotencyPort idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final TableResourceLocks locks;
    private final ReservationTableMapper reservationTables;

    public CheckServiceImpl(
            CheckMapper checks,
            TableAreaMapper areas,
            TableMapper tables,
            TableOccupancyMapper occupancies,
            SeatMapper seats,
            TableOperationMapper operations,
            GuestSessionMapper sessions,
            ActionLogMapper actions,
            StoreOperationService policies,
            RequestIdempotencyPort idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json,
            @Qualifier(ModuleDataSources.TRADE_TX_MANAGER) PlatformTransactionManager manager,
            FoodIndustryOperationsMapper industry,
            @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager industryManager) {
        this(
                checks,
                areas,
                tables,
                occupancies,
                seats,
                operations,
                sessions,
                actions,
                policies,
                idem,
                publisher,
                json,
                manager,
                null,
                industry,
                industryManager);
    }

    @Autowired
    public CheckServiceImpl(
            CheckMapper checks,
            TableAreaMapper areas,
            TableMapper tables,
            TableOccupancyMapper occupancies,
            SeatMapper seats,
            TableOperationMapper operations,
            GuestSessionMapper sessions,
            ActionLogMapper actions,
            StoreOperationService policies,
            RequestIdempotencyPort idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json,
            @Qualifier(ModuleDataSources.TRADE_TX_MANAGER) PlatformTransactionManager manager,
            ReservationTableMapper reservationTables,
            FoodIndustryOperationsMapper industry,
            @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager industryManager) {
        this.reservationTables = reservationTables;
        this.industry = industry;
        this.industryTx = new TransactionTemplate(industryManager);
        industryTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.checks = checks;
        this.tables = tables;
        this.occupancies = occupancies;
        this.seats = seats;
        this.operations = operations;
        this.sessions = sessions;
        this.actions = actions;
        this.policies = policies;
        this.idem = idem;
        this.publisher = publisher;
        this.json = json;
        this.locks = new TableResourceLocks(areas, tables);
        this.tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public CheckResultVO openCheck(
            String entity, String store, String actor, OpenCheckReq req, String key) {
        return open(entity, store, actor, req, key, "OPEN_CHECK");
    }

    @Override
    public CheckResultVO openQueuedCheck(
            String entity, String store, String actor, OpenCheckReq req, String key) {
        return open(entity, store, actor, req, key, "OPEN_CHECK", true);
    }

    @Override
    public CheckResultVO openReservedCheck(
            String entity,
            String store,
            String actor,
            OpenCheckReq req,
            String key,
            Instant reservedUntil) {
        if (reservationTables == null
                || reservedUntil == null
                || !reservedUntil.isAfter(Instant.now())
                || reservedUntil.getNano() % 1_000_000 != 0)
            throw BizException.of(ErrorCode.CONFLICT);
        // 订位到店也走「先占桌、后建账单、失败补偿」，由订位服务在自己的事务之外调用。
        return open(entity, store, actor, req, key, "OPEN_RESERVED_CHECK", false, reservedUntil);
    }

    private CheckResultVO open(
            String entity,
            String store,
            String actor,
            OpenCheckReq req,
            String key,
            String action) {
        return open(entity, store, actor, req, key, action, false);
    }

    private CheckResultVO open(
            String entity,
            String store,
            String actor,
            OpenCheckReq req,
            String key,
            String action,
            boolean queued) {
        return open(entity, store, actor, req, key, action, queued, null);
    }

    private CheckResultVO open(
            String entity,
            String store,
            String actor,
            OpenCheckReq req,
            String key,
            String action,
            boolean queued,
            Instant reservedUntil) {
        if (req == null || req.partySize() == null || req.partySize() < 1) bad();
        payMode(req.payMode());
        unverified(req.reservationNo());
        unverified(req.serviceEmployeeNo());
        if (!Set.of("EXCLUSIVE", "SHARED")
                .contains(req.sharingMode() == null ? "" : req.sharingMode())) bad();
        var targets = numbers(req.tableNos(), 100);
        var versions = versions(req.tableVersions(), 100);
        if (!versions.keySet().equals(new HashSet<>(targets))) bad();
        var desired = seatInputs(req.seats(), req.partySize());
        if (desired.stream().anyMatch(s -> s.seatNo() != null)) bad();
        String originalHash =
                FoodCommandFingerprint.hash(
                        json.writeValueAsString(targets),
                        req.partySize().toString(),
                        req.payMode(),
                        req.sharingMode(),
                        json.writeValueAsString(versions),
                        json.writeValueAsString(desired));
        // Preserve historical walk-in/queue fingerprints; only reservations bind a bounded
        // occupancy window.
        String hash =
                reservedUntil == null
                        ? originalHash
                        : FoodCommandFingerprint.hash(
                                originalHash, "RESERVED_UNTIL", reservedUntil.toString());
        if (!queued)
            return openWithCompensation(
                    entity, store, actor, req, key, action, hash, targets, versions, desired, reservedUntil);
        // 排队入座、订位到店仍在调用方的事务里开台（随排队、订位改补偿时一起改，见 TDD §6）。
        return write(
                action,
                entity,
                store,
                actor,
                key,
                hash,
                CheckResultVO.class,
                queued,
                () -> {
                    var locked = locks.lock(entity, store, targets, versions, true);
                    var allocation =
                            allocation(
                                    entity,
                                    locked,
                                    req.partySize(),
                                    req.sharingMode(),
                                    null,
                                    reservedUntil);
                    var at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                    var policy = policies.getPolicy(entity, store, new GetPolicyQuery(at));
                    if (!req.payMode().equals(policy.payPolicy()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var check = new FnbCheck();
                    check.setEntityNo(entity);
                    check.setStoreNo(store);
                    check.setCheckNo(BizKey.next("FCHK"));
                    check.setBusinessDate(ServiceCalendarPolicy.businessDate(at, policy));
                    check.setPolicyNo(policy.policyNo());
                    check.setPartySize(req.partySize());
                    check.setPayMode(req.payMode());
                    check.setStatus("OPEN");
                    check.setOpenedAt(local(at));
                    check.setServiceState("OPEN");
                    initialize(check, actor);
                    checks.insert(check);
                    occupy(check, allocation, req.sharingMode(), actor);
                    saveSeatRows(check, desired, actor);
                    touch(entity, locked, actor);
                    String operation = BizKey.next("FOPR");
                    if (action.equals("SHARE_TABLE"))
                        operation(
                                check,
                                operation,
                                action,
                                key,
                                hash,
                                Map.of("tableVersions", versions),
                                actor,
                                "独立拼桌");
                    changed(check, actor, action, null, null, key, operation);
                    return new CheckResultVO(view(check));
                });
    }

    /**
     * 开台（散客、拼桌）：订单域与业务域分开，跨域用补偿（ADR-068 修订二）。
     *
     * <ol>
     *   <li>业务域（行业库）先占桌：锁桌、按座位分配、写占台、更新翻台。账单号由请求键派生，占台引用它；
     *       同一请求重放时占台已在，不再占。
     *   <li>订单域（交易库）按同一个账单号建账单与座位；账单已在就按它返回（业务键回查）。
     *   <li>订单域失败且账单不存在：结束本次占台（补偿）。结果不明时留给 {@link #recoverOpenings}。
     * </ol>
     */
    private CheckResultVO openWithCompensation(
            String entity,
            String store,
            String actor,
            OpenCheckReq req,
            String key,
            String action,
            String hash,
            List<String> targets,
            Map<String, Long> versions,
            List<SeatReq> desired,
            Instant reservedUntil) {
        scope(entity, store);
        if (actor == null || actor.isBlank() || actor.length() > 32)
            throw BizException.of(ErrorCode.FORBIDDEN);
        text(key, 128);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Check command cannot join an existing transaction");
        String checkNo = CommandBusinessKey.derive("FCHK", action, entity, store, actor, key);
        String operationNo = CommandBusinessKey.derive("FOPR", action, entity, store, actor, key);
        var existing = checks.selectOne(
                Wrappers.<FnbCheck>lambdaQuery()
                        .eq(FnbCheck::getEntityNo, entity)
                        .eq(FnbCheck::getCheckNo, checkNo));
        if (existing != null) return new CheckResultVO(view(existing));
        var at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var policy = policies.getPolicy(entity, store, new GetPolicyQuery(at));
        if (!req.payMode().equals(policy.payPolicy())) throw BizException.of(ErrorCode.CONFLICT);
        occupyFirst(
                entity, store, actor, req, key, action, hash, checkNo, operationNo, targets, versions, reservedUntil);
        try {
            return write(
                    action,
                    entity,
                    store,
                    actor,
                    key,
                    hash,
                    CheckResultVO.class,
                    () -> {
                        var replayed = checks.selectOne(
                                Wrappers.<FnbCheck>lambdaQuery()
                                        .eq(FnbCheck::getEntityNo, entity)
                                        .eq(FnbCheck::getCheckNo, checkNo));
                        if (replayed != null) return new CheckResultVO(view(replayed));
                        // 占台已被补偿结束的（超时扫描），不再建账单；不占桌的开台没有占台。
                        if (!targets.isEmpty() && occupancies.activeForCheck(entity, checkNo).isEmpty())
                            throw BizException.of(ErrorCode.CONFLICT);
                        var check = new FnbCheck();
                        check.setEntityNo(entity);
                        check.setStoreNo(store);
                        check.setCheckNo(checkNo);
                        check.setBusinessDate(ServiceCalendarPolicy.businessDate(at, policy));
                        check.setPolicyNo(policy.policyNo());
                        check.setPartySize(req.partySize());
                        check.setPayMode(req.payMode());
                        check.setStatus("OPEN");
                        check.setOpenedAt(local(at));
                        check.setServiceState("OPEN");
                        initialize(check, actor);
                        checks.insert(check);
                        saveSeatRows(check, desired, actor);
                        changed(check, actor, action, null, null, key, operationNo);
                        return new CheckResultVO(view(check));
                    });
        } catch (RuntimeException failure) {
            compensateOpening(entity, store, checkNo, actor);
            throw failure;
        }
    }

    /** 业务域：锁桌、分配座位、写占台、更新翻台；本次请求已占过（重放）就不再占。 */
    private void occupyFirst(
            String entity,
            String store,
            String actor,
            OpenCheckReq req,
            String key,
            String action,
            String hash,
            String checkNo,
            String operationNo,
            List<String> targets,
            Map<String, Long> versions,
            Instant reservedUntil) {
        try {
            industryTx.executeWithoutResult(
                    status -> {
                        if (targets.isEmpty() || !occupancies.activeForCheck(entity, checkNo).isEmpty()) return;
                        var locked = locks.lock(entity, store, targets, versions, true);
                        var allocation =
                                allocation(
                                        entity, locked, req.partySize(), req.sharingMode(), null, reservedUntil);
                        var owner = new FnbCheck();
                        owner.setEntityNo(entity);
                        owner.setStoreNo(store);
                        owner.setCheckNo(checkNo);
                        owner.setPartySize(req.partySize());
                        owner.setPayMode(req.payMode());
                        owner.setVersion(0L);
                        occupy(owner, allocation, req.sharingMode(), actor);
                        touch(entity, locked, actor);
                        // 补偿后用同一请求重试：操作记录已在（操作号由请求键派生），不再写。
                        if (action.equals("SHARE_TABLE")
                                && operations.selectCount(
                                                Wrappers.<FnbTableOperation>lambdaQuery()
                                                        .eq(FnbTableOperation::getEntityNo, entity)
                                                        .eq(FnbTableOperation::getOperationNo, operationNo))
                                        == 0)
                            operation(
                                    owner,
                                    operationNo,
                                    action,
                                    key,
                                    hash,
                                    Map.of("tableVersions", versions),
                                    actor,
                                    "独立拼桌");
                    });
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** 补偿：账单在订单域不存在时，结束这张账单号上的占台并更新翻台（可重复执行）。 */
    private void compensateOpening(String entity, String store, String checkNo, String actor) {
        if (checks.selectCount(
                        Wrappers.<FnbCheck>lambdaQuery()
                                .eq(FnbCheck::getEntityNo, entity)
                                .eq(FnbCheck::getCheckNo, checkNo))
                > 0) return;
        industryTx.executeWithoutResult(
                status -> {
                    var active = occupancies.activeForCheck(entity, checkNo);
                    if (active.isEmpty()) return;
                    var locked = locks.lock(entity, store, tableNos(active), null, false);
                    endOccupancies(occupancies.activeForCheck(entity, checkNo), actor);
                    touch(entity, locked, actor);
                });
    }

    /**
     * 清台补做的扫描（订单域为准）：账单已作废、结账或放台（状态或服务状态不再是 OPEN），占台却还在的，结束占台并更新翻台。
     * 返回处理的账单数。
     */
    public int recoverClosings(int limit) {
        int ended = 0;
        for (var row : industry.activeOccupancyChecks(limit)) {
            String entity = row.text("entity_no"), store = row.text("store_no"), number = row.text("check_no");
            var check = checks.selectOne(
                    Wrappers.<FnbCheck>lambdaQuery()
                            .eq(FnbCheck::getEntityNo, entity)
                            .eq(FnbCheck::getCheckNo, number));
            if (check == null
                    || "OPEN".equals(check.getStatus()) && "OPEN".equals(check.getServiceState())) continue;
            industryTx.executeWithoutResult(
                    status -> {
                        var still = active(check);
                        if (still.isEmpty()) return;
                        var locked = locks.lock(entity, store, tableNos(still), null, false);
                        endOccupancies(still, "SYSTEM");
                        touch(entity, locked, "SYSTEM");
                    });
            ended++;
        }
        return ended;
    }

    /**
     * 开台补偿的扫描：开台超过 {@code olderThan} 仍没有账单的占台，结束它（订单域为准）。
     * 返回结束的账单号数。
     */
    public int recoverOpenings(java.time.Duration olderThan, int limit) {
        var now = Instant.now();
        var cutoff = LocalDateTime.ofInstant(now.minus(olderThan), ZoneOffset.UTC);
        // 只看一天内开的：漏建账单发生在开台那几秒，正常在用的占台一天内数量有限（桌台数）。
        var since = LocalDateTime.ofInstant(now.minus(java.time.Duration.ofDays(1)), ZoneOffset.UTC);
        int ended = 0;
        for (var row : industry.activeOccupanciesStartedBetween(since, cutoff, limit)) {
            String entity = row.text("entity_no"), store = row.text("store_no"), check = row.text("check_no");
            if (checks.selectCount(
                            Wrappers.<FnbCheck>lambdaQuery()
                                    .eq(FnbCheck::getEntityNo, entity)
                                    .eq(FnbCheck::getCheckNo, check))
                    > 0) continue;
            compensateOpening(entity, store, check, "SYSTEM");
            ended++;
        }
        return ended;
    }

    @Override
    public CheckResultVO openSharedCheck(
            String entity,
            String store,
            String table,
            String actor,
            OpenSharedCheckReq req,
            String key) {
        id(table);
        if (req == null) bad();
        version(req.version());
        return open(
                entity,
                store,
                actor,
                new OpenCheckReq(
                        List.of(table),
                        req.partySize(),
                        req.payMode(),
                        null,
                        List.of(new TableVersionReq(table, req.version())),
                        "SHARED",
                        List.of(),
                        null),
                key,
                "SHARE_TABLE");
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.TRADE_TX_MANAGER, readOnly = true)
    public PageData<CheckVO> listChecks(String entity, String store, ListChecksQuery query) {
        scope(entity, store);
        int page = page(query == null ? null : query.page());
        int size = size(query == null ? null : query.size());
        String state = query == null ? null : query.status();
        if (state != null
                && !Set.of("OPEN", "SETTLING", "CLOSED", "MERGED", "VOIDED").contains(state)) bad();
        var filter =
                Wrappers.<FnbCheck>lambdaQuery()
                        .eq(FnbCheck::getEntityNo, entity)
                        .eq(FnbCheck::getStoreNo, store)
                        .eq(state != null, FnbCheck::getStatus, state)
                        .eq(
                                query != null && query.businessDate() != null,
                                FnbCheck::getBusinessDate,
                                query == null ? null : query.businessDate())
                        .orderByDesc(FnbCheck::getOpenedAt)
                        .orderByDesc(FnbCheck::getCheckNo);
        var result = checks.selectPage(new Page<FnbCheck>(page, size), filter);
        return PageData.of(
                result.getRecords().stream().map(this::view).toList(),
                result.getTotal(),
                page,
                size);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.TRADE_TX_MANAGER, readOnly = true)
    public CheckDetailVO getCheck(String entity, String store, String number) {
        scope(entity, store);
        id(number);
        var check = find(entity, store, number, false);
        var slots = seatViews(check);
        var active = active(check);
        return new CheckDetailVO(
                view(check, active),
                slots,
                active.stream()
                        .map(
                                row ->
                                        new OccupancyVO(
                                                row.getOccupancyNo(),
                                                row.getTableNo(),
                                                row.getCheckNo(),
                                                row.getSeats(),
                                                row.getStartedAt().toInstant(ZoneOffset.UTC)))
                        .toList());
    }

    @Override
    public CheckResultVO saveSeats(
            String entity,
            String store,
            String number,
            String actor,
            SaveCheckSeatsReq req,
            String key) {
        id(number);
        if (req == null || req.partySize() == null || req.partySize() < 1) bad();
        version(req.version());
        String reason = text(req.reason(), 255);
        var desired = seatInputs(req.seats(), req.partySize());
        String hash =
                FoodCommandFingerprint.hash(
                        number,
                        req.version().toString(),
                        req.partySize().toString(),
                        reason,
                        json.writeValueAsString(desired));
        commandScope(entity, store, actor, key);
        String operationNo = CommandBusinessKey.derive("FOPR", "SAVE_SEATS", entity, store, actor, key);
        // ① 业务域：按新人数重新分配各桌座位（容量是这里的约束）；账单只读一次比对版本与交易事实。
        var before = new java.util.concurrent.atomic.AtomicReference<CheckVO>();
        var beforeSeats = new java.util.concurrent.atomic.AtomicReference<List<SeatReq>>();
        if (!applied(entity, number, operationNo))
            reseat(entity, store, number, actor, req.version(), req.partySize(), before, beforeSeats);
        try {
            // ② 订单域：座位、人数、账单版本、日志与事件。
            return orderStep(
                    "SAVE_SEATS",
                    entity,
                    store,
                    actor,
                    key,
                    hash,
                    CheckResultVO.class,
                    number,
                    operationNo,
                    check -> {
                        if (check.getVersion() != req.version()
                                || !"OPEN".equals(check.getStatus())
                                || !"OPEN".equals(check.getServiceState()))
                            throw BizException.of(ErrorCode.CONFLICT);
                        noTrade(check);
                        saveSeatRows(check, desired, actor);
                        check.setPartySize(req.partySize());
                        update(check, actor);
                        changed(
                                check,
                                actor,
                                "SAVE_SEATS",
                                reason,
                                before.get() == null ? view(check) : before.get(),
                                key,
                                operationNo,
                                beforeSeats.get() == null ? List.of() : beforeSeats.get());
                        return new CheckResultVO(view(check));
                    },
                    check -> new CheckResultVO(view(check)));
        } catch (RuntimeException failure) {
            // 补偿：按账单当前人数把座位分配回去（订单域为准）。
            var current = find(entity, store, number, false);
            if (current.getPartySize() != null && !current.getPartySize().equals(req.partySize()))
                reseat(entity, store, number, actor, null, current.getPartySize(), null, null);
            throw failure;
        }
    }

    /** 只读桌台行（行业库），不加锁。 */
    private List<FnbTable> tableRows(String entity, String store, List<String> numbers) {
        if (numbers.isEmpty()) return List.of();
        return tables.selectList(
                Wrappers.<FnbTable>lambdaQuery()
                        .eq(FnbTable::getEntityNo, entity)
                        .eq(FnbTable::getStoreNo, store)
                        .in(FnbTable::getTableNo, numbers));
    }

    /** 订单域日志里已有这个操作号：这次请求已经做完（重放）。 */
    private boolean applied(String entity, String number, String operationNo) {
        return actions.selectCount(
                        Wrappers.<FnbActionLog>lambdaQuery()
                                .eq(FnbActionLog::getEntityNo, entity)
                                .eq(FnbActionLog::getObjectType, "CHECK")
                                .eq(FnbActionLog::getObjectNo, number)
                                .eq(FnbActionLog::getActionNo, operationNo))
                > 0;
    }

    /** 业务域：按人数重新分配这张账单各桌的座位并更新翻台；expectedVersion 为空时不比对（补偿用）。 */
    private void reseat(
            String entity,
            String store,
            String number,
            String actor,
            Long expectedVersion,
            int partySize,
            java.util.concurrent.atomic.AtomicReference<CheckVO> before,
            java.util.concurrent.atomic.AtomicReference<List<SeatReq>> beforeSeats) {
        try {
            industryTx.executeWithoutResult(
                    status -> {
                        var check = find(entity, store, number, false);
                        if (expectedVersion != null) {
                            if (check.getVersion() != expectedVersion.longValue()
                                    || !"OPEN".equals(check.getStatus())
                                    || !"OPEN".equals(check.getServiceState()))
                                throw BizException.of(ErrorCode.CONFLICT);
                            noTrade(check);
                            if (businessGuard != null) {
                                businessGuard.assertMutable(entity, store, number);
                                businessGuard.assertPartyMutable(entity, store, number);
                            }
                            before.set(view(check));
                            beforeSeats.set(seatViews(check));
                        }
                        var active = active(check);
                        var locked = locks.lock(entity, store, tableNos(active), null, true);
                        localResources(active, locked);
                        var allocation = allocation(entity, locked, partySize, mode(active), number);
                        for (var row : active) {
                            row.setSeats(allocation.get(row.getTableNo()));
                            if (occupancies.updateById(row) != 1)
                                throw BizException.of(ErrorCode.CONFLICT);
                        }
                        touch(entity, locked, actor);
                    });
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public OperationVO clearCheck(
            String entity,
            String store,
            String number,
            String actor,
            ClearCheckReq req,
            String key) {
        id(number);
        if (req == null) bad();
        version(req.version());
        unverified(req.approvalRef());
        String reason = text(req.reason(), 255);
        var requested = numbers(req.occupancyNos(), 100);
        String hash =
                FoodCommandFingerprint.hash(
                        number,
                        req.version().toString(),
                        reason,
                        json.writeValueAsString(requested));
        commandScope(entity, store, actor, key);
        String operationNo = CommandBusinessKey.derive("FOPR", "CLEAR", entity, store, actor, key);
        if (applied(entity, number, operationNo)) {
            var check = find(entity, store, number, false);
            return new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number);
        }
        var current = find(entity, store, number, false);
        var active = active(current);
        var chosen = active.stream().filter(o -> requested.contains(o.getOccupancyNo())).toList();
        if (chosen.size() != requested.size()) throw BizException.of(ErrorCode.NOT_FOUND);
        if (chosen.isEmpty() && !active.isEmpty()) bad();
        Map<String, Object> expected = Map.of("checkVersion", req.version(), "occupancyNos", requested);
        // 先在两边各读一次：有交易事实、待处理或外部占用的不清台（原先在同一事务里查，顺序不变）。
        noTradeFacts(current);
        localResources(active, tableRows(entity, store, tableNos(active)));
        if (chosen.size() == active.size()) {
            // 全部清台、账单作废：订单域先（以订单为准），业务域随后结束占台；没做成的由清台补做扫描结束。
            var result =
                    orderStep(
                            "CLEAR",
                            entity,
                            store,
                            actor,
                            key,
                            hash,
                            OperationVO.class,
                            number,
                            operationNo,
                            check -> {
                                if (businessGuard != null)
                                    businessGuard.assertMutable(entity, store, number);
                                if (check.getVersion() != req.version()
                                        || !"OPEN".equals(check.getStatus())
                                        || !"OPEN".equals(check.getServiceState()))
                                    throw BizException.of(ErrorCode.CONFLICT);
                                noTradeFacts(check);
                                var before = view(check);
                                check.setServiceState("RELEASED");
                                check.setStatus("VOIDED");
                                check.setClosedAt(now());
                                revokeCheckSessions(check, actor);
                                update(check, actor);
                                changed(check, actor, "CLEAR", reason, before, key, operationNo);
                                return new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number);
                            },
                            check -> new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number));
            industryStep(
                    entity,
                    operationNo,
                    () -> {
                        var check = find(entity, store, number, false);
                        var still = active(check);
                        var locked = locks.lock(entity, store, tableNos(still), null, false);
                        endOccupancies(still, actor);
                        touch(entity, locked, actor);
                        operation(check, operationNo, "CLEAR", key, hash, expected, actor, reason);
                    });
            return result;
        }
        // 部分清台：只改业务域（结束选中的占台、其余重新分配座位），订单域随后给账单加版本、记事件。
        var before = new java.util.concurrent.atomic.AtomicReference<CheckVO>();
        industryStep(
                entity,
                operationNo,
                () -> {
                    var check = find(entity, store, number, false);
                    if (businessGuard != null) businessGuard.assertMutable(entity, store, number);
                    if (check.getVersion() != req.version()
                            || !"OPEN".equals(check.getStatus())
                            || !"OPEN".equals(check.getServiceState()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    noTradeFacts(check);
                    before.set(view(check));
                    var live = active(check);
                    var locked = locks.lock(entity, store, tableNos(live), null, false);
                    localResources(live, locked);
                    var remaining =
                            live.stream().filter(row -> !requested.contains(row.getOccupancyNo())).toList();
                    var remainingTables =
                            locked.stream()
                                    .filter(table -> tableNos(remaining).contains(table.getTableNo()))
                                    .toList();
                    var allocation =
                            allocation(entity, remainingTables, check.getPartySize(), mode(live), number);
                    for (var row : remaining) {
                        row.setSeats(allocation.get(row.getTableNo()));
                        if (row.getVersion() == Long.MAX_VALUE || occupancies.updateById(row) != 1)
                            throw BizException.of(ErrorCode.CONFLICT);
                    }
                    endOccupancies(
                            live.stream().filter(row -> requested.contains(row.getOccupancyNo())).toList(),
                            actor);
                    touch(entity, locked, actor);
                    check.setVersion(check.getVersion() + 1);
                    operation(check, operationNo, "CLEAR", key, hash, expected, actor, reason);
                });
        return orderStep(
                "CLEAR",
                entity,
                store,
                actor,
                key,
                hash,
                OperationVO.class,
                number,
                operationNo,
                check -> {
                    update(check, actor);
                    changed(check, actor, "CLEAR", reason, before.get(), key, operationNo);
                    return new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number);
                },
                check -> new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number));
    }

    @Override
    public OperationResultVO transferCheck(
            String entity,
            String store,
            String number,
            String actor,
            TransferCheckReq req,
            String key) {
        id(number);
        if (req == null) bad();
        version(req.version());
        unverified(req.approvalRef());
        var targets = numbers(req.targetTableNos(), 100);
        if (targets.isEmpty()) bad();
        var versions = versions(req.tableVersions(), 200);
        String reason = text(req.reason(), 255);
        return move(
                entity,
                store,
                number,
                actor,
                req.version(),
                targets,
                versions,
                reason,
                key,
                "TRANSFER");
    }

    @Override
    public OperationResultVO combineTables(
            String entity,
            String store,
            String number,
            String actor,
            CombineTablesReq req,
            String key) {
        id(number);
        if (req == null) bad();
        version(req.version());
        String reason = text(req.reason(), 255);
        var versions = versions(req.tableVersions(), 100);
        if (versions.isEmpty()) bad();
        return move(
                entity,
                store,
                number,
                actor,
                req.version(),
                List.copyOf(versions.keySet()),
                versions,
                reason,
                key,
                "COMBINE_TABLES");
    }

    /**
     * 换台、并台只改业务域的占台（ADR-068 修订二）：
     * ① 业务域（行业库）挪占台、更新翻台、写操作记录，操作号由请求键派生，已做过就跳过；账单只读一次比对版本；
     * ② 订单域（交易库）给账单加版本、记日志与事件，日志里已有这个操作号就按重放返回。
     * ② 失败不补偿：占台已经挪好，用同一请求重试补上账单这一步。
     */
    private OperationResultVO move(
            String entity,
            String store,
            String number,
            String actor,
            long version,
            List<String> targets,
            Map<String, Long> versions,
            String reason,
            String key,
            String action) {
        String hash =
                FoodCommandFingerprint.hash(
                        number,
                        Long.toString(version),
                        reason,
                        json.writeValueAsString(targets),
                        json.writeValueAsString(versions));
        commandScope(entity, store, actor, key);
        String operationNo = CommandBusinessKey.derive("FOPR", action, entity, store, actor, key);
        var before = new java.util.concurrent.atomic.AtomicReference<CheckVO>();
        industryStep(
                entity,
                operationNo,
                () -> {
                    var check = find(entity, store, number, false);
                    if (businessGuard != null) businessGuard.assertMutable(entity, store, number);
                    if (check.getVersion() != version) throw BizException.of(ErrorCode.CONFLICT);
                    movable(check);
                    before.set(view(check));
                    var active = active(check);
                    var old = tableNos(active);
                    var all =
                            Stream.concat(old.stream(), targets.stream())
                                    .distinct()
                                    .sorted()
                                    .toList();
                    boolean combine = action.equals("COMBINE_TABLES");
                    if (combine && targets.stream().anyMatch(old::contains)) bad();
                    if (!combine && !versions.keySet().equals(new HashSet<>(all))) bad();
                    var locked = locks.lock(entity, store, all, combine ? null : versions, true);
                    localResources(active, locked);
                    if (combine)
                        for (var row : locked)
                            if (versions.containsKey(row.getTableNo())
                                    && !row.getVersion().equals(versions.get(row.getTableNo())))
                                throw BizException.of(ErrorCode.CONFLICT);
                    var destination = combine ? all : targets;
                    var targetRows =
                            locked.stream()
                                    .filter(t -> destination.contains(t.getTableNo()))
                                    .toList();
                    String mode = mode(active);
                    var allocation =
                            allocation(entity, targetRows, check.getPartySize(), mode, number);
                    // 先占新桌、再结束旧占台，都在这一个行业事务里。
                    occupy(check, allocation, mode, actor);
                    endOccupancies(active, actor);
                    touch(entity, locked, actor);
                    // 操作记录里的结果版本是订单域那一步加版本之后的值（只加一次）。
                    check.setVersion(check.getVersion() + 1);
                    operation(
                            check,
                            operationNo,
                            action,
                            key,
                            hash,
                            Map.of("checkVersion", version, "tableVersions", versions),
                            actor,
                            reason);
                });
        return orderStep(
                action,
                entity,
                store,
                actor,
                key,
                hash,
                OperationResultVO.class,
                number,
                operationNo,
                check -> {
                    update(check, actor);
                    changed(check, actor, action, reason, before.get(), key, operationNo);
                    return new OperationResultVO(
                            new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number));
                },
                check ->
                        new OperationResultVO(
                                new OperationVO(operationNo, "SUCCEEDED", check.getVersion(), number)));
    }

    private void commandScope(String entity, String store, String actor, String key) {
        scope(entity, store);
        if (actor == null || actor.isBlank() || actor.length() > 32)
            throw BizException.of(ErrorCode.FORBIDDEN);
        text(key, 128);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Check command cannot join an existing transaction");
    }

    /** 业务域一步：在行业事务里执行；这个操作号的操作记录已在（重放）就跳过。 */
    private void industryStep(String entity, String operationNo, Runnable work) {
        try {
            industryTx.executeWithoutResult(
                    status -> {
                        if (operations.selectCount(
                                        Wrappers.<FnbTableOperation>lambdaQuery()
                                                .eq(FnbTableOperation::getEntityNo, entity)
                                                .eq(FnbTableOperation::getOperationNo, operationNo))
                                > 0) return;
                        work.run();
                    });
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /**
     * 订单域一步：锁账单、执行；账单日志里已有这个操作号（重放、或请求防重已过期）就不再执行，按当前账单返回。
     */
    private <T> T orderStep(
            String action,
            String entity,
            String store,
            String actor,
            String key,
            String hash,
            Class<T> type,
            String number,
            String operationNo,
            java.util.function.Function<FnbCheck, T> work,
            java.util.function.Function<FnbCheck, T> replay) {
        return write(
                action,
                entity,
                store,
                actor,
                key,
                hash,
                type,
                () -> {
                    var check = find(entity, store, number, true);
                    if (actions.selectCount(
                                    Wrappers.<FnbActionLog>lambdaQuery()
                                            .eq(FnbActionLog::getEntityNo, entity)
                                            .eq(FnbActionLog::getObjectType, "CHECK")
                                            .eq(FnbActionLog::getObjectNo, number)
                                            .eq(FnbActionLog::getActionNo, operationNo))
                            > 0) return replay.apply(check);
                    return work.apply(check);
                });
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.TRADE_TX_MANAGER, readOnly = true)
    public OperationResultVO getTableOperation(String entity, String store, String operation) {
        scope(entity, store);
        id(operation);
        var rows =
                operations.selectList(
                        Wrappers.<FnbTableOperation>lambdaQuery()
                                .eq(FnbTableOperation::getEntityNo, entity)
                                .eq(FnbTableOperation::getStoreNo, store)
                                .eq(FnbTableOperation::getOperationNo, operation)
                                .last("LIMIT 1"));
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var row = rows.getFirst();
        String state = row.getStatus();
        if (state == null
                || !Set.of("PREPARED", "CLAIMING", "APPLIED", "RECOVERING", "FAILED")
                        .contains(state)) throw BizException.of(ErrorCode.CONFLICT);
        long resultVersion = row.getVersion();
        if ("APPLIED".equals(state)) {
            var snapshot = json.readTree(row.getTargetScope());
            var value = snapshot == null ? null : snapshot.get("resultVersion");
            if (value == null
                    || !value.isIntegralNumber()
                    || !value.canConvertToLong()
                    || value.asLong() < 0) throw BizException.of(ErrorCode.CONFLICT);
            resultVersion = value.asLong();
        }
        return new OperationResultVO(
                new OperationVO(
                        operation,
                        "APPLIED".equals(state) ? "SUCCEEDED" : state,
                        resultVersion,
                        row.getCheckNo()));
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.TRADE_TX_MANAGER, readOnly = true)
    public PageData<ActionLogVO> listActions(
            String entity, String store, String number, ListCheckActionsQuery query) {
        scope(entity, store);
        id(number);
        find(entity, store, number, false);
        int page = page(query == null ? null : query.page());
        int size = size(query == null ? null : query.size());
        var result =
                actions.selectPage(
                        new Page<FnbActionLog>(page, size),
                        Wrappers.<FnbActionLog>lambdaQuery()
                                .eq(FnbActionLog::getEntityNo, entity)
                                .eq(FnbActionLog::getStoreNo, store)
                                .eq(FnbActionLog::getObjectType, "CHECK")
                                .eq(FnbActionLog::getObjectNo, number)
                                .orderByDesc(FnbActionLog::getCreatedAt)
                                .orderByDesc(FnbActionLog::getActionNo));
        return PageData.of(
                result.getRecords().stream()
                        .map(
                                a ->
                                        new ActionLogVO(
                                                a.getActionNo(),
                                                a.getAction(),
                                                a.getOperatorNo(),
                                                a.getCreatedAt()
                                                        .atZone(ZoneId.systemDefault())
                                                        .toInstant(),
                                                a.getReason()))
                        .toList(),
                result.getTotal(),
                page,
                size);
    }

    private <T> T write(
            String action,
            String entity,
            String store,
            String actor,
            String key,
            String hash,
            Class<T> type,
            Supplier<T> work) {
        return write(action, entity, store, actor, key, hash, type, false, work);
    }

    private <T> T write(
            String action,
            String entity,
            String store,
            String actor,
            String key,
            String hash,
            Class<T> type,
            boolean queued,
            Supplier<T> work) {
        scope(entity, store);
        if (actor == null || actor.isBlank() || actor.length() > 32)
            throw BizException.of(ErrorCode.FORBIDDEN);
        text(key, 128);
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        if (queued ? !active : active)
            throw new IllegalStateException(
                    queued
                            ? "Queued check requires its queue transaction"
                            : "Check command cannot join an existing transaction");
        try {
            return tx.execute(
                    status -> {
                        // REQUIRED must participate in this manager, not start a second database
                        // transaction.
                        if (queued && status.isNewTransaction())
                            throw new IllegalStateException(
                                    "Queued check requires the primary queue transaction");
                        return idem.execute(
                                key,
                                FoodCommandFingerprint.scope(action, entity, store),
                                actor,
                                hash,
                                type,
                                work);
                    });
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private FnbCheck find(String entity, String store, String number, boolean lock) {
        var rows =
                checks.selectList(
                        Wrappers.<FnbCheck>lambdaQuery()
                                .eq(FnbCheck::getEntityNo, entity)
                                .eq(FnbCheck::getStoreNo, store)
                                .eq(FnbCheck::getCheckNo, number)
                                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private FnbCheck locked(String entity, String store, String number, long version) {
        var row = find(entity, store, number, true);
        if (businessGuard != null) businessGuard.assertMutable(entity, store, number);
        if (row.getVersion() != version
                || !"OPEN".equals(row.getStatus())
                || !"OPEN".equals(row.getServiceState())) throw BizException.of(ErrorCode.CONFLICT);
        return row;
    }

    private static void localResources(List<FnbTableOccupancy> active, List<FnbTable> tables) {
        if (active.stream().anyMatch(row -> row.getResourceClaimNo() != null)
                || tables.stream().anyMatch(row -> row.getResourceNo() != null))
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    /**
     * Same-check topology preserves trade identities; check settlement states and pending external
     * operations stay closed.
     */
    private void movable(FnbCheck row) {
        if (!"OPEN".equals(row.getStatus())
                || !"OPEN".equals(row.getServiceState())
                || !("PREPAY".equals(row.getPayMode()) || "POSTPAY".equals(row.getPayMode()))
                || operations.externalOrPendingForCheck(row.getEntityNo(), row.getCheckNo()) > 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void noTrade(FnbCheck row) {
        if (!"PREPAY".equals(row.getPayMode())) throw BizException.of(ErrorCode.CONFLICT);
        noTradeFacts(row);
    }

    /** Empty POSTPAY reception has no receivable; any original trade/unknown fact still blocks release. */
    private void noTradeFacts(FnbCheck row) {
        if (!("PREPAY".equals(row.getPayMode()) || "POSTPAY".equals(row.getPayMode()))
                || checks.tradeFacts(row.getEntityNo(), row.getCheckNo()) > 0
                || industry.buffetEntitlements(
                                row.getEntityNo(), checks.formNos(row.getEntityNo(), row.getCheckNo()), false)
                        > 0
                || operations.externalOrPendingForCheck(row.getEntityNo(), row.getCheckNo()) > 0)
            throw BizException.of(ErrorCode.CONFLICT);
    }

    private List<FnbTableOccupancy> active(FnbCheck row) {
        var active = occupancies.activeForCheck(row.getEntityNo(), row.getCheckNo());
        if (active.stream().map(FnbTableOccupancy::getTableNo).distinct().count() != active.size())
            throw BizException.of(ErrorCode.CONFLICT);
        return active;
    }

    private Map<String, Integer> allocation(
            String entity, List<FnbTable> tables, int party, String mode, String own) {
        return allocation(entity, tables, party, mode, own, null);
    }

    private Map<String, Integer> allocation(
            String entity,
            List<FnbTable> tables,
            int party,
            String mode,
            String own,
            Instant reservedUntil) {
        var at = Instant.now();
        if (reservedUntil != null && !reservedUntil.isAfter(at))
            throw BizException.of(ErrorCode.CONFLICT);
        if (reservationTables != null)
            for (var table : tables) {
                long conflicts =
                        reservedUntil == null
                                ? reservationTables.pending(
                                        entity, table.getStoreNo(), table.getTableNo(), local(at))
                                : reservationTables.overlaps(
                                        entity,
                                        table.getStoreNo(),
                                        table.getTableNo(),
                                        local(at),
                                        local(reservedUntil),
                                        null);
                if (conflicts > 0) throw BizException.of(ErrorCode.CONFLICT);
            }
        var active = new LinkedHashMap<String, List<FnbTableOccupancy>>();
        for (var table : tables)
            active.put(table.getTableNo(), occupancies.activeForTable(entity, table.getTableNo()));
        return TableOccupancyPolicy.allocate(tables, active, party, mode, own);
    }

    private void occupy(
            FnbCheck check, Map<String, Integer> allocation, String mode, String actor) {
        for (var target : allocation.entrySet()) {
            var row = new FnbTableOccupancy();
            row.setEntityNo(check.getEntityNo());
            row.setCheckNo(check.getCheckNo());
            row.setOccupancyNo(BizKey.next("FOCC"));
            row.setTableNo(target.getKey());
            row.setSeats(target.getValue());
            row.setStartedAt(now());
            row.setSharingMode(mode);
            initialize(row, actor);
            occupancies.insert(row);
        }
    }

    private void endOccupancies(List<FnbTableOccupancy> active, String actor) {
        for (var row : active) {
            row.setEndedAt(now());
            if (row.getVersion() == Long.MAX_VALUE || occupancies.updateById(row) != 1)
                throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void touch(String entity, List<FnbTable> locked, String actor) {
        for (var table : locked) {
            boolean empty = occupancies.activeForTable(entity, table.getTableNo()).isEmpty();
            table.setTurnoverState(empty ? "IDLE" : "IN_USE");
            if (table.getVersion() == Long.MAX_VALUE || tables.updateById(table) != 1)
                throw BizException.of(ErrorCode.CONFLICT);
            if (empty)
                sessions.update(
                        null,
                        Wrappers.<FnbGuestSession>lambdaUpdate()
                                .eq(FnbGuestSession::getEntityNo, entity)
                                .eq(FnbGuestSession::getStoreNo, table.getStoreNo())
                                .eq(FnbGuestSession::getTableNo, table.getTableNo())
                                .isNull(FnbGuestSession::getCheckNo)
                                .isNull(FnbGuestSession::getRevokedAt)
                                .set(FnbGuestSession::getRevokedAt, now())
                                .set(FnbGuestSession::getUpdatedBy, actor)
                                .set(FnbGuestSession::getUpdatedAt, LocalDateTime.now()));
        }
    }

    private void revokeCheckSessions(FnbCheck check, String actor) {
        sessions.update(
                null,
                Wrappers.<FnbGuestSession>lambdaUpdate()
                        .eq(FnbGuestSession::getEntityNo, check.getEntityNo())
                        .eq(FnbGuestSession::getStoreNo, check.getStoreNo())
                        .eq(FnbGuestSession::getCheckNo, check.getCheckNo())
                        .isNull(FnbGuestSession::getRevokedAt)
                        .set(FnbGuestSession::getRevokedAt, now())
                        .set(FnbGuestSession::getUpdatedBy, actor)
                        .set(FnbGuestSession::getUpdatedAt, LocalDateTime.now()));
    }

    private void saveSeatRows(FnbCheck check, List<SeatReq> desired, String actor) {
        var history = seats.history(check.getEntityNo(), check.getCheckNo());
        var retained = new HashSet<String>();
        for (var input : desired) {
            FnbSeat row = null;
            if (input.seatNo() != null)
                row =
                        history.stream()
                                .filter(s -> s.getSeatNo().equals(input.seatNo()))
                                .findFirst()
                                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            else
                row =
                        history.stream()
                                .filter(s -> s.getLabel().equals(input.label()))
                                .findFirst()
                                .orElse(null);
            if (row == null) {
                row = new FnbSeat();
                row.setEntityNo(check.getEntityNo());
                row.setCheckNo(check.getCheckNo());
                row.setSeatNo(BizKey.next("FSET"));
                row.setLabel(input.label());
                initialize(row, actor);
                seats.insert(row);
            } else {
                // Untraded free-label changes keep seat identity; uniqueness conflicts roll back
                // the whole request.
                if (!row.getLabel().equals(input.label()) || row.getDeleted() != 0) {
                    if (row.getVersion() == Long.MAX_VALUE
                            || seats.save(
                                            check.getEntityNo(),
                                            check.getCheckNo(),
                                            row.getSeatNo(),
                                            row.getVersion(),
                                            input.label(),
                                            0,
                                            actor,
                                            LocalDateTime.now())
                                    != 1) throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            if (!retained.add(row.getSeatNo())) bad();
        }
        for (var row : history)
            if (row.getDeleted() == 0 && !retained.contains(row.getSeatNo())) {
                if (row.getVersion() == Long.MAX_VALUE
                        || seats.save(
                                        check.getEntityNo(),
                                        check.getCheckNo(),
                                        row.getSeatNo(),
                                        row.getVersion(),
                                        row.getLabel(),
                                        1,
                                        actor,
                                        LocalDateTime.now())
                                != 1) throw BizException.of(ErrorCode.CONFLICT);
            }
    }

    private void operation(
            FnbCheck check,
            String number,
            String kind,
            String key,
            String hash,
            Object expected,
            String actor,
            String reason) {
        var row = new FnbTableOperation();
        row.setEntityNo(check.getEntityNo());
        row.setStoreNo(check.getStoreNo());
        row.setOperationNo(number);
        row.setKind(kind);
        row.setCheckNo(check.getCheckNo());
        row.setRequestKey(FoodCommandFingerprint.hash(check.getEntityNo(), kind, actor, key));
        row.setRequestHash(hash);
        row.setExpectedVersions(json.writeValueAsString(expected));
        row.setTargetScope(
                json.writeValueAsString(
                        Map.of(
                                "tableNos",
                                view(check).tableNos(),
                                "partySize",
                                check.getPartySize(),
                                "resultVersion",
                                check.getVersion())));
        row.setStatus("APPLIED");
        row.setReason(reason);
        initialize(row, actor);
        operations.insert(row);
    }

    private void changed(
            FnbCheck check,
            String actor,
            String action,
            String reason,
            CheckVO before,
            String key,
            String operation) {
        changed(
                check,
                actor,
                action,
                reason,
                before,
                key,
                operation,
                before == null ? List.of() : seatViews(check));
    }

    private void changed(
            FnbCheck check,
            String actor,
            String action,
            String reason,
            CheckVO before,
            String key,
            String operation,
            List<SeatReq> beforeSeats) {
        var after = view(check);
        var afterSeats = seatViews(check);
        var row = new FnbActionLog();
        row.setEntityNo(check.getEntityNo());
        row.setStoreNo(check.getStoreNo());
        row.setObjectType("CHECK");
        row.setObjectNo(check.getCheckNo());
        row.setActionNo(operation);
        row.setAction(action);
        row.setOperatorNo(actor);
        row.setReason(reason);
        row.setRequestNo(
                FoodCommandFingerprint.hash(
                        FoodCommandFingerprint.scope(
                                action, check.getEntityNo(), check.getStoreNo()),
                        key));
        row.setBeforeRef(
                before == null
                        ? null
                        : FoodCommandFingerprint.hash(
                                json.writeValueAsString(before),
                                json.writeValueAsString(beforeSeats)));
        row.setAfterRef(
                FoodCommandFingerprint.hash(
                        json.writeValueAsString(after), json.writeValueAsString(afterSeats)));
        initialize(row, actor);
        actions.insert(row);
        publisher.publish(
                new CheckChanged(
                        check.getEntityNo(),
                        check.getStoreNo(),
                        check.getCheckNo(),
                        action,
                        check.getVersion(),
                        operation,
                        before,
                        after,
                        reason,
                        beforeSeats,
                        afterSeats),
                new PublishOptions("FOOD_CHECK:" + check.getCheckNo(), 1));
    }

    private List<SeatReq> seatViews(FnbCheck check) {
        return seats
                .selectList(
                        Wrappers.<FnbSeat>lambdaQuery()
                                .eq(FnbSeat::getEntityNo, check.getEntityNo())
                                .eq(FnbSeat::getCheckNo, check.getCheckNo())
                                .orderByAsc(FnbSeat::getSeatNo))
                .stream()
                .map(row -> new SeatReq(row.getSeatNo(), row.getLabel(), null))
                .toList();
    }

    private void update(FnbCheck row, String actor) {
        if (row.getVersion() == Long.MAX_VALUE || checks.updateById(row) != 1)
            throw BizException.of(ErrorCode.CONFLICT);
    }

    private CheckVO view(FnbCheck check) {
        return view(check, active(check));
    }

    private CheckVO view(FnbCheck check, List<FnbTableOccupancy> active) {
        return new CheckVO(
                check.getCheckNo(),
                check.getStoreNo(),
                check.getBusinessDate(),
                check.getStatus(),
                check.getPayMode(),
                check.getPartySize(),
                tableNos(active),
                check.getVersion());
    }

    private static String mode(List<FnbTableOccupancy> active) {
        if (active.isEmpty()) return "EXCLUSIVE";
        String mode = active.getFirst().getSharingMode();
        if (mode == null || active.stream().anyMatch(row -> !mode.equals(row.getSharingMode())))
            throw BizException.of(ErrorCode.CONFLICT);
        return mode;
    }

    private static List<String> tableNos(List<FnbTableOccupancy> active) {
        return active.stream().map(FnbTableOccupancy::getTableNo).distinct().sorted().toList();
    }

    private static List<SeatReq> seatInputs(List<SeatReq> inputs, int party) {
        if (inputs == null || inputs.size() > 1000 || inputs.size() > party) bad();
        var normalized = new ArrayList<SeatReq>();
        var labels = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var input : inputs) {
            if (input == null) bad();
            unverified(input.guestRef());
            String label = text(input.label(), 32);
            String number = input.seatNo();
            if (number != null) id(number);
            if (!labels.add(label) || number != null && !ids.add(number)) bad();
            normalized.add(new SeatReq(number, label, null));
        }
        normalized.sort(Comparator.comparing(SeatReq::label));
        return List.copyOf(normalized);
    }

    private static Map<String, Long> versions(List<TableVersionReq> inputs, int max) {
        if (inputs == null || inputs.size() > max) bad();
        var result = new TreeMap<String, Long>();
        for (var input : inputs) {
            if (input == null) bad();
            id(input.tableNo());
            version(input.version());
            if (result.put(input.tableNo(), input.version()) != null) bad();
        }
        return result;
    }

    private static List<String> numbers(Collection<String> inputs, int max) {
        if (inputs == null || inputs.size() > max) bad();
        var copy = new ArrayList<String>();
        for (String input : inputs) {
            id(input);
            copy.add(input);
        }
        if (new HashSet<>(copy).size() != copy.size()) bad();
        return copy.stream().sorted().toList();
    }

    private static void initialize(BaseEntity row, String actor) {
        row.setVersion(0L);
        row.setDeleted(0);
    }

    private static void payMode(String mode) {
        if (!"PREPAY".equals(mode) && !"POSTPAY".equals(mode)) bad();
    }

    private static void unverified(String ref) {
        if (ref != null && !ref.isBlank()) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private static void scope(String entity, String store) {
        id(entity);
        id(store);
    }

    private static void id(String value) {
        text(value, 32);
    }

    private static void version(Long value) {
        if (value == null || value < 0) bad();
    }

    private static int page(Integer value) {
        if (value != null && value < 1) bad();
        return value == null ? 1 : value;
    }

    private static int size(Integer value) {
        if (value != null && (value < 1 || value > 100)) bad();
        return value == null ? 20 : value;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) bad();
        return value.trim();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static LocalDateTime now() {
        return local(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
