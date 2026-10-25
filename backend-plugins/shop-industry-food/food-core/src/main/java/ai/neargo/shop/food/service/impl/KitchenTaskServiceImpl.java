package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.KitchenContracts.CoreLineScope;
import ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseVO;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.CustodySourceLine;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoute;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverLine;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.KitchenTaskScope;
import ai.neargo.shop.food.dto.KitchenContracts.PackingReq;
import ai.neargo.shop.food.dto.KitchenContracts.RemakeReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbCourse;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CourseMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.CustodyFulfillmentPolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.KitchenQuantityPolicy;
import ai.neargo.shop.food.policy.StationPublicationDocuments;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Batch locks serialize task deltas and independent station operations without remote work under
 * locks.
 */
public class KitchenTaskServiceImpl implements KitchenTaskService {
    private final CoreOrderBindingMapper bindings;
    private final CoreLineAllocationMapper allocations;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final OrderComponentMapper components;
    private final ConfigRevisionMapper revisions;
    private final KitchenTicketMapper tickets;
    private final KitchenTaskMapper tasks;
    private final ServiceEventMapper events;
    private final CourseMapper courses;
    private final CheckMapper checks;
    private final IdempotencyService idem;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;
    private final TransactionTemplate write;
    private ObjectProvider<FoodStationRuntimeService> stationRuntime;

    @Autowired
    public void configureStationRuntime(ObjectProvider<FoodStationRuntimeService> stationRuntime) {
        this.stationRuntime = stationRuntime;
    }

    private void running(String entity, String store, String station) {
        if (stationRuntime != null) {
            var runtime = stationRuntime.getIfUnique();
            if (runtime == null) throw conflict();
            runtime.requireRunning(entity, store, station);
        }
    }

    private ObjectProvider<ServiceSlotService> slots;

    @Autowired
    public void configureSlots(ObjectProvider<ServiceSlotService> slots) {
        this.slots = slots;
    }

    private ObjectProvider<IndustryFulfillmentPort> fulfillment;

    @Autowired
    public void configureFulfillment(ObjectProvider<IndustryFulfillmentPort> fulfillment) {
        this.fulfillment = fulfillment;
    }

    public KitchenTaskServiceImpl(
            OrderBatchMapper batches,
            OrderLineMapper lines,
            OrderComponentMapper components,
            ConfigRevisionMapper revisions,
            KitchenTicketMapper tickets,
            KitchenTaskMapper tasks,
            ServiceEventMapper events,
            CourseMapper courses,
            CheckMapper checks,
            IdempotencyService idem,
            ObjectMapper json,
            PlatformTransactionManager manager,
            OutboxPublisher outbox) {
        this(
                batches,
                lines,
                components,
                revisions,
                tickets,
                tasks,
                events,
                courses,
                checks,
                idem,
                json,
                manager,
                outbox,
                null,
                null);
    }

    public KitchenTaskServiceImpl(
            OrderBatchMapper batches,
            OrderLineMapper lines,
            OrderComponentMapper components,
            ConfigRevisionMapper revisions,
            KitchenTicketMapper tickets,
            KitchenTaskMapper tasks,
            ServiceEventMapper events,
            CourseMapper courses,
            CheckMapper checks,
            IdempotencyService idem,
            ObjectMapper json,
            PlatformTransactionManager manager,
            OutboxPublisher outbox,
            CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations) {
        this.bindings = bindings;
        this.allocations = allocations;
        this.batches = batches;
        this.lines = lines;
        this.components = components;
        this.revisions = revisions;
        this.tickets = tickets;
        this.tasks = tasks;
        this.events = events;
        this.courses = courses;
        this.checks = checks;
        this.idem = idem;
        this.json = json;
        this.outbox = outbox;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public FrozenRoutes freezeRoutes(String entity, String store, List<String> skus) {
        context(entity, store);
        if (skus == null || skus.isEmpty() || skus.size() > 1000) throw bad();
        skus.forEach(KitchenTaskServiceImpl::id);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var found = new ArrayList<FrozenRoute>();
                    var seen = new HashSet<String>();
                    for (var revision : tickets.stationPublications(entity)) {
                        // Consider only the latest effective publication of each station, including
                        // store mismatch.
                        if (!seen.add(revision.getConfigNo())) continue;
                        var body =
                                StationPublicationDocuments.validate(
                                        json,
                                        revision.getConfigNo(),
                                        revision.getDefinitionSnapshot());
                        if (!entity.equals(body.entityNo()) || !store.equals(body.storeNo()))
                            continue;
                        for (var route : body.routes())
                            if (skus.contains(route.skuNo())) {
                                found.add(
                                        new FrozenRoute(
                                                route.skuNo(),
                                                body.configNo(),
                                                route.taskRole(),
                                                route.routeVersion(),
                                                revision.getRevisionNo(),
                                                revision.getContentHash()));
                            }
                    }
                    for (String sku : skus)
                        if (found.stream().noneMatch(r -> r.skuNo().equals(sku))) throw conflict();
                    return new FrozenRoutes(found);
                });
    }

    @Override
    public TicketVO accepted(String entity, String store, String batchNo, String dispatchKey) {
        context(entity, store);
        id(batchNo);
        key(dispatchKey);
        return tx(
                () -> {
                    var batch = batch(entity, store, batchNo, true);
                    if (!"ACCEPTED".equals(batch.getStatus()) || batch.getAcceptedAt() == null)
                        throw conflict();
                    if (batch.getScheduledAt() != null) {
                        var service = slots == null ? null : slots.getIfUnique();
                        if (service == null) throw conflict();
                        if (!service.dispatchDue(entity, store, batchNo))
                            return new TicketVO(
                                    null, batchNo, "MAKE", null, "SCHEDULED", List.of());
                    }
                    var existing =
                            tickets.selectList(
                                    Wrappers.<FnbKitchenTicket>lambdaQuery()
                                            .eq(FnbKitchenTicket::getEntityNo, entity)
                                            .eq(FnbKitchenTicket::getBatchNo, batchNo)
                                            .eq(FnbKitchenTicket::getKind, "MAKE"));
                    if (!existing.isEmpty()) return ticket(entity, existing.getFirst());
                    var frozen = json.readTree(batch.getFrozenRequest()).get("kitchenRoutes");
                    if (frozen == null || frozen.isNull()) throw conflict();
                    var routes = json.treeToValue(frozen, FrozenRoutes.class);
                    validateRoutes(entity, store, routes);
                    var orderLines =
                            lines.selectList(
                                    Wrappers.<FnbOrderLine>lambdaQuery()
                                            .eq(FnbOrderLine::getEntityNo, entity)
                                            .eq(FnbOrderLine::getBatchNo, batchNo)
                                            .orderByAsc(FnbOrderLine::getFoodLineNo));
                    if (orderLines.isEmpty()) throw conflict();
                    var head = new FnbKitchenTicket();
                    stamp(head, "SYSTEM");
                    head.setEntityNo(entity);
                    head.setTicketNo(BizKey.next("FKIT"));
                    head.setBatchNo(batchNo);
                    head.setDispatchKey(
                            FoodCommandFingerprint.hash(
                                    "kitchen-dispatch:v1", entity, store, batchNo));
                    head.setKind("MAKE");
                    head.setStatus("QUEUED");
                    tickets.insert(head);
                    for (var line : orderLines) {
                        var parts =
                                components.selectList(
                                        Wrappers.<FnbOrderComponent>lambdaQuery()
                                                .eq(FnbOrderComponent::getEntityNo, entity)
                                                .eq(
                                                        FnbOrderComponent::getFoodLineNo,
                                                        line.getFoodLineNo()));
                        // Persisted component quantities are the frozen executable units, never
                        // caller deltas.
                        if (parts.isEmpty())
                            addTasks(
                                    entity,
                                    head,
                                    line,
                                    null,
                                    line.getSkuNo(),
                                    line.getQty(),
                                    routes);
                        else {
                            var makeRoutes =
                                    new FrozenRoutes(
                                            routes.routes().stream()
                                                    .filter(r -> "MAKE".equals(r.taskRole()))
                                                    .toList());
                            for (var part : parts)
                                if (!"MAIN".equals(part.getComponentKind()))
                                    addTasks(
                                            entity,
                                            head,
                                            line,
                                            part.getComponentNo(),
                                            part.getSkuNo(),
                                            part.getQty(),
                                            makeRoutes);
                            boolean bundled =
                                    parts.stream()
                                            .anyMatch(
                                                    p ->
                                                            p.getComponentKind() != null
                                                                    && p.getComponentKind()
                                                                            .startsWith("BUNDLE_"));
                            var parentRoutes =
                                    routes.routes().stream()
                                            .filter(
                                                    r ->
                                                            r.skuNo().equals(line.getSkuNo())
                                                                    && (!bundled
                                                                            || !"MAKE"
                                                                                    .equals(
                                                                                            r
                                                                                                    .taskRole())))
                                            .toList();
                            if (!parentRoutes.isEmpty())
                                addTasks(
                                        entity,
                                        head,
                                        line,
                                        null,
                                        line.getSkuNo(),
                                        line.getQty(),
                                        new FrozenRoutes(parentRoutes));
                        }
                    }
                    refreshTicket(entity, head.getTicketNo(), "SYSTEM");
                    return ticket(entity, head(entity, head.getTicketNo()));
                });
    }

    private void validateRoutes(String entity, String store, FrozenRoutes frozen) {
        if (frozen == null || frozen.routes().isEmpty() || frozen.routes().size() > 5000)
            throw conflict();
        var seen = new HashSet<String>();
        for (var route : frozen.routes()) {
            id(route.skuNo());
            id(route.stationNo());
            id(route.revisionNo());
            if (!seen.add(route.skuNo() + ":" + route.stationNo() + ":" + route.taskRole()))
                throw conflict();
            var row = revisions.findRevision(entity, route.revisionNo());
            if (row == null
                    || !"STATION".equals(row.getConfigKind())
                    || !route.stationNo().equals(row.getConfigNo())
                    || !row.getContentHash().equals(route.contentHash())) throw conflict();
            var body =
                    StationPublicationDocuments.validate(
                            json, row.getConfigNo(), row.getDefinitionSnapshot());
            if (!entity.equals(body.entityNo())
                    || !store.equals(body.storeNo())
                    || body.routes().stream()
                            .noneMatch(
                                    r ->
                                            r.skuNo().equals(route.skuNo())
                                                    && r.taskRole().equals(route.taskRole())
                                                    && r.routeVersion() == route.routeVersion()))
                throw conflict();
        }
    }

    private void addTasks(
            String entity,
            FnbKitchenTicket head,
            FnbOrderLine line,
            String component,
            String sku,
            BigDecimal qty,
            FrozenRoutes frozen) {
        KitchenQuantityPolicy.positive(qty);
        BigDecimal cancelled = tickets.cancelledMainQty(entity, line.getFoodLineNo());
        if (cancelled == null) cancelled = BigDecimal.ZERO;
        if (cancelled.signum() < 0 || cancelled.compareTo(line.getQty()) > 0) throw conflict();
        if (cancelled.compareTo(line.getQty()) == 0) return;
        if (cancelled.signum() > 0)
            qty =
                    KitchenReturnServiceImpl.quantity(
                            line.getQty().subtract(cancelled), qty, line.getQty());
        var matched = frozen.routes().stream().filter(r -> sku.equals(r.skuNo())).toList();
        if (matched.isEmpty()) throw conflict();
        String state = "QUEUED";
        if (line.getCourseNo() != null) {
            var course = course(entity, line.getCourseNo(), false);
            var batch = batch(entity, null, line.getBatchNo(), false);
            if (!course.getCheckNo().equals(batch.getCheckNo())
                    || "CLOSED".equals(course.getStatus())) throw conflict();
            if ("HELD".equals(course.getStatus())) state = "HELD";
        }
        for (var route : matched) {
            var row = new FnbKitchenTask();
            stamp(row, "SYSTEM");
            row.setEntityNo(entity);
            row.setTaskNo(BizKey.next("FTSK"));
            row.setTicketNo(head.getTicketNo());
            row.setFoodLineNo(line.getFoodLineNo());
            row.setComponentNo(component);
            row.setTaskPartKey(component == null ? line.getFoodLineNo() : component);
            row.setStationNo(route.stationNo());
            row.setRouteVersion(route.routeVersion());
            row.setTaskRole(route.taskRole());
            row.setQty(qty);
            row.setMakingQty(BigDecimal.ZERO);
            row.setReadyQty(BigDecimal.ZERO);
            row.setServedQty(BigDecimal.ZERO);
            row.setVoidQty(BigDecimal.ZERO);
            row.setStatus(state);
            tasks.insert(row);
        }
    }

    @Override
    public List<TaskVO> queue(String entity, String store, String station, String batchNo) {
        context(entity, store);
        if (station != null) id(station);
        if (batchNo != null) id(batchNo);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    boolean active = batchNo == null;
                    var activeBatches =
                            active
                                    ? tasks.activeBatchNos(entity, store, station)
                                    : List.<String>of();
                    if (active && activeBatches.isEmpty()) return List.of();
                    var heads =
                            batches.selectList(
                                    Wrappers.<FnbOrderBatch>lambdaQuery()
                                            .eq(FnbOrderBatch::getTenantNo, "MAIN")
                                            .eq(FnbOrderBatch::getEntityNo, entity)
                                            .eq(FnbOrderBatch::getStoreNo, store)
                                            .eq(!active, FnbOrderBatch::getBatchNo, batchNo)
                                            .in(active, FnbOrderBatch::getBatchNo, activeBatches)
                                            .eq(FnbOrderBatch::getStatus, "ACCEPTED")
                                            .orderByAsc(FnbOrderBatch::getAcceptedAt)
                                            .orderByAsc(FnbOrderBatch::getBatchNo));
                    var result = new ArrayList<TaskVO>();
                    for (var batch : heads)
                        for (var head :
                                tickets.selectList(
                                        Wrappers.<FnbKitchenTicket>lambdaQuery()
                                                .eq(FnbKitchenTicket::getTenantNo, "MAIN")
                                                .eq(FnbKitchenTicket::getEntityNo, entity)
                                                .eq(
                                                        FnbKitchenTicket::getBatchNo,
                                                        batch.getBatchNo())
                                                .ne(active, FnbKitchenTicket::getStatus, "CLOSED")
                                                .orderByAsc(FnbKitchenTicket::getCreatedAt)
                                                .orderByAsc(FnbKitchenTicket::getTicketNo))) {
                            for (var task :
                                    tasks.selectList(
                                            Wrappers.<FnbKitchenTask>lambdaQuery()
                                                    .eq(FnbKitchenTask::getTenantNo, "MAIN")
                                                    .eq(FnbKitchenTask::getEntityNo, entity)
                                                    .eq(
                                                            FnbKitchenTask::getTicketNo,
                                                            head.getTicketNo())
                                                    .eq(
                                                            station != null,
                                                            FnbKitchenTask::getStationNo,
                                                            station)
                                                    .notIn(
                                                            active,
                                                            FnbKitchenTask::getStatus,
                                                            List.of("SERVED", "VOIDED"))
                                                    .orderByAsc(FnbKitchenTask::getCreatedAt)
                                                    .orderByAsc(FnbKitchenTask::getTaskNo)))
                                result.add(view(task, batch.getBatchNo()));
                        }
                    return List.copyOf(result);
                });
    }

    @Override
    public EventVO act(
            String entity,
            String store,
            String actor,
            String taskNo,
            TaskActionReq req,
            String key) {
        context(entity, store);
        id(actor);
        id(taskNo);
        key(key);
        if (req == null
                || req.action() == null
                || req.version() == null
                || req.version() < 0
                || req.version() == Long.MAX_VALUE) throw bad();
        KitchenQuantityPolicy.positive(req.qty());
        reason(req.action(), req.reason());
        return command(
                entity,
                store,
                actor,
                key,
                "TASK_ACTION",
                taskNo,
                req,
                EventVO.class,
                () -> {
                    var task = lockedTask(entity, store, taskNo);
                    if (req.version() == null || !req.version().equals(task.getVersion()))
                        throw conflict();
                    if (Set.of("START", "FIRE").contains(req.action()))
                        running(entity, store, task.getStationNo());
                    if (("HOLD".equals(req.action()) || "FIRE".equals(req.action()))
                            && req.qty().compareTo(task.getQty()) != 0) throw bad();
                    if (Set.of("START", "READY").contains(req.action())) {
                        stageReady(
                                entity,
                                task,
                                "START".equals(req.action())
                                        ? task.getMakingQty().add(req.qty())
                                        : task.getReadyQty().add(req.qty()));
                    }
                    BigDecimal servedReturn = BigDecimal.ZERO;
                    BigDecimal workLoss = BigDecimal.ZERO;
                    boolean returned = "RETURN_DISPOSE".equals(req.action());
                    if (returned) {
                        servedReturn =
                                req.qty()
                                        .min(
                                                task.getServedQty()
                                                        .subtract(
                                                                factQty(
                                                                        entity,
                                                                        taskNo,
                                                                        "RETURN_LOSS")));
                        workLoss = req.qty().subtract(servedReturn);
                        if (workLoss.signum() > 0)
                            KitchenQuantityPolicy.apply(
                                    task,
                                    "LOSS",
                                    workLoss,
                                    loss(entity, taskNo),
                                    readyLoss(entity, taskNo));
                    } else
                        KitchenQuantityPolicy.apply(
                                task,
                                req.action(),
                                req.qty(),
                                loss(entity, taskNo),
                                readyLoss(entity, taskNo));
                    stampUpdate(task, actor);
                    if (tasks.updateById(task) != 1) throw conflict();
                    FnbServiceEvent event = null;
                    String originalOperation = operation(entity, store, key);
                    if (returned) {
                        if (workLoss.signum() > 0)
                            event =
                                    append(
                                            entity,
                                            actor,
                                            taskNo,
                                            "LOSS",
                                            workLoss,
                                            req.reason(),
                                            FoodCommandFingerprint.hash(
                                                    "RETURN_WORK_LOSS", originalOperation));
                        if (servedReturn.signum() > 0)
                            event =
                                    append(
                                            entity,
                                            actor,
                                            taskNo,
                                            "RETURN_LOSS",
                                            servedReturn,
                                            req.reason(),
                                            originalOperation);
                    } else
                        event =
                                append(
                                        entity,
                                        actor,
                                        taskNo,
                                        req.action(),
                                        req.qty(),
                                        req.reason(),
                                        originalOperation);
                    if (event == null) throw conflict();
                    refreshTicket(entity, task.getTicketNo(), actor);
                    return event(event);
                });
    }

    @Override
    public TicketVO remake(
            String entity, String store, String actor, String taskNo, RemakeReq req, String key) {
        context(entity, store);
        id(actor);
        id(taskNo);
        key(key);
        if (req == null
                || req.version() == null
                || req.version() < 0
                || req.version() == Long.MAX_VALUE) throw bad();
        KitchenQuantityPolicy.positive(req.qty());
        reason("REMAKE", req.reason());
        return command(
                entity,
                store,
                actor,
                key,
                "REMAKE",
                taskNo,
                req,
                TicketVO.class,
                () -> {
                    var original = lockedTask(entity, store, taskNo);
                    if (req.version() == null || !req.version().equals(original.getVersion()))
                        throw conflict();
                    var parent = head(entity, original.getTicketNo());
                    var children =
                            tickets.selectList(
                                    Wrappers.<FnbKitchenTicket>lambdaQuery()
                                            .eq(FnbKitchenTicket::getEntityNo, entity)
                                            .eq(
                                                    FnbKitchenTicket::getOriginalTicketNo,
                                                    parent.getTicketNo())
                                            .eq(FnbKitchenTicket::getKind, "REMAKE"));
                    BigDecimal prior = BigDecimal.ZERO;
                    for (var child : children)
                        for (var task : ticketTasks(entity, child.getTicketNo())) {
                            if (taskNo.equals(task.getTaskPartKey()))
                                prior = prior.add(task.getQty());
                        }
                    if (prior.add(req.qty()).compareTo(loss(entity, taskNo)) > 0) throw conflict();
                    var head = new FnbKitchenTicket();
                    stamp(head, actor);
                    head.setEntityNo(entity);
                    head.setTicketNo(BizKey.next("FKIT"));
                    head.setBatchNo(parent.getBatchNo());
                    head.setKind("REMAKE");
                    head.setOriginalTicketNo(parent.getTicketNo());
                    head.setDispatchKey(operation(entity, store, key));
                    head.setStatus("QUEUED");
                    tickets.insert(head);
                    var task = new FnbKitchenTask();
                    stamp(task, actor);
                    task.setEntityNo(entity);
                    task.setTaskNo(BizKey.next("FTSK"));
                    task.setTicketNo(head.getTicketNo());
                    task.setFoodLineNo(original.getFoodLineNo());
                    task.setComponentNo(original.getComponentNo());
                    task.setStationNo(original.getStationNo());
                    task.setRouteVersion(original.getRouteVersion());
                    task.setTaskRole(original.getTaskRole());
                    task.setTaskPartKey(taskNo);
                    task.setQty(req.qty());
                    task.setMakingQty(BigDecimal.ZERO);
                    task.setReadyQty(BigDecimal.ZERO);
                    task.setServedQty(BigDecimal.ZERO);
                    task.setVoidQty(BigDecimal.ZERO);
                    task.setStatus("QUEUED");
                    tasks.insert(task);
                    append(
                            entity,
                            actor,
                            taskNo,
                            "REMAKE",
                            req.qty(),
                            req.reason(),
                            operation(entity, store, key));
                    return ticket(entity, head);
                });
    }

    @Override
    public List<EventVO> events(String entity, String store, String taskNo) {
        context(entity, store);
        id(taskNo);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    scopedTask(entity, store, taskNo);
                    return events
                            .selectList(
                                    Wrappers.<FnbServiceEvent>lambdaQuery()
                                            .eq(FnbServiceEvent::getEntityNo, entity)
                                            .eq(FnbServiceEvent::getTaskNo, taskNo)
                                            .orderByAsc(FnbServiceEvent::getId))
                            .stream()
                            .map(KitchenTaskServiceImpl::event)
                            .toList();
                });
    }

    @Override
    public CourseVO createCourse(
            String entity, String store, String actor, String checkNo, CourseReq req, String key) {
        context(entity, store);
        id(actor);
        id(checkNo);
        key(key);
        if (req == null
                || req.name() == null
                || req.name().isBlank()
                || req.name().length() > 64
                || req.sequenceNo() == null
                || req.sequenceNo() < 1
                || req.sequenceNo() > 1000) throw bad();
        return command(
                entity,
                store,
                actor,
                key,
                "CREATE_COURSE",
                checkNo,
                req,
                CourseVO.class,
                () -> {
                    check(entity, store, checkNo, true);
                    var row = new FnbCourse();
                    stamp(row, actor);
                    row.setEntityNo(entity);
                    row.setCourseNo(BizKey.next("FCRS"));
                    row.setCheckNo(checkNo);
                    row.setName(req.name());
                    row.setSequenceNo(req.sequenceNo());
                    row.setStatus("HELD");
                    courses.insert(row);
                    return courseView(row);
                });
    }

    @Override
    public List<CourseVO> courses(String entity, String store, String checkNo) {
        context(entity, store);
        id(checkNo);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    check(entity, store, checkNo, false);
                    return courses
                            .selectList(
                                    Wrappers.<FnbCourse>lambdaQuery()
                                            .eq(FnbCourse::getEntityNo, entity)
                                            .eq(FnbCourse::getCheckNo, checkNo)
                                            .orderByAsc(FnbCourse::getSequenceNo))
                            .stream()
                            .map(KitchenTaskServiceImpl::courseView)
                            .toList();
                });
    }

    @Override
    public CourseVO actCourse(
            String entity,
            String store,
            String actor,
            String courseNo,
            CourseActionReq req,
            String key) {
        context(entity, store);
        id(actor);
        id(courseNo);
        key(key);
        if (req == null
                || req.action() == null
                || req.version() == null
                || req.version() < 0
                || req.version() == Long.MAX_VALUE
                || !Set.of("HOLD", "FIRE", "CLOSE").contains(req.action())) throw bad();
        reason(req.action(), req.reason());
        if (req.seatNos() != null) {
            if (req.seatNos().size() > 100
                    || new HashSet<>(req.seatNos()).size() != req.seatNos().size()) throw bad();
            req.seatNos().forEach(KitchenTaskServiceImpl::id);
            if (!req.seatNos().isEmpty() && "CLOSE".equals(req.action())) throw bad();
        }
        return command(
                entity,
                store,
                actor,
                key,
                "COURSE_ACTION",
                courseNo,
                req,
                CourseVO.class,
                () -> {
                    var current = course(entity, courseNo, false);
                    check(entity, store, current.getCheckNo(), true);
                    // Lock batches in stable order, the same lock hierarchy as task operations;
                    // lock course last.
                    var orderBatches =
                            batches.selectList(
                                    Wrappers.<FnbOrderBatch>lambdaQuery()
                                            .eq(FnbOrderBatch::getEntityNo, entity)
                                            .eq(FnbOrderBatch::getStoreNo, store)
                                            .eq(FnbOrderBatch::getCheckNo, current.getCheckNo())
                                            .orderByAsc(FnbOrderBatch::getBatchNo)
                                            .last("FOR UPDATE"));
                    current = course(entity, courseNo, true);
                    if (req.version() == null || !req.version().equals(current.getVersion()))
                        throw conflict();
                    if ("CLOSED".equals(current.getStatus())) throw conflict();
                    for (var batch : orderBatches)
                        for (var ticket :
                                tickets.selectList(
                                        Wrappers.<FnbKitchenTicket>lambdaQuery()
                                                .eq(FnbKitchenTicket::getEntityNo, entity)
                                                .eq(
                                                        FnbKitchenTicket::getBatchNo,
                                                        batch.getBatchNo()))) {
                            for (var task : ticketTasks(entity, ticket.getTicketNo())) {
                                var line = line(entity, task.getFoodLineNo());
                                if (!courseNo.equals(line.getCourseNo())) continue;
                                if (req.seatNos() != null
                                        && !req.seatNos().isEmpty()
                                        && !req.seatNos().contains(line.getSeatNo())) continue;
                                if ("CLOSE".equals(req.action())) {
                                    if (!Set.of("SERVED", "VOIDED").contains(task.getStatus()))
                                        throw conflict();
                                } else {
                                    if ("FIRE".equals(req.action()))
                                        running(entity, store, task.getStationNo());
                                    String desired =
                                            "FIRE".equals(req.action()) ? "QUEUED" : "HELD";
                                    if (desired.equals(task.getStatus())) continue;
                                    KitchenQuantityPolicy.apply(
                                            task,
                                            req.action(),
                                            task.getQty(),
                                            loss(entity, task.getTaskNo()));
                                    stampUpdate(task, actor);
                                    if (tasks.updateById(task) != 1) throw conflict();
                                    append(
                                            entity,
                                            actor,
                                            task.getTaskNo(),
                                            req.action(),
                                            task.getQty(),
                                            req.reason(),
                                            FoodCommandFingerprint.hash(
                                                    "course-task:v1",
                                                    entity,
                                                    store,
                                                    key,
                                                    task.getTaskNo()));
                                }
                            }
                            refreshTicket(entity, ticket.getTicketNo(), actor);
                        }
                    current.setStatus(
                            switch (req.action()) {
                                case "HOLD" -> "HELD";
                                case "FIRE" ->
                                        req.seatNos() == null || req.seatNos().isEmpty()
                                                ? "FIRED"
                                                : current.getStatus();
                                default -> "CLOSED";
                            });
                    stampUpdate(current, actor);
                    if (courses.updateById(current) != 1) throw conflict();
                    return courseView(current);
                });
    }

    @Override
    public CustodyReceiptVO confirmPacking(
            String entity, String store, String actor, String batchNo, PackingReq req, String key) {
        context(entity, store);
        id(actor);
        id(batchNo);
        key(key);
        if (req == null) throw bad();
        validateCustodyLines(req.lines());
        reason("HOLD", req.reason());
        return command(
                entity,
                store,
                actor,
                key,
                "PACKING",
                batchNo,
                req,
                CustodyReceiptVO.class,
                () ->
                        recordCustody(
                                entity,
                                store,
                                actor,
                                batchNo,
                                req.lines(),
                                req.reason(),
                                null,
                                null,
                                true,
                                key));
    }

    @Override
    public HandoverVO handover(
            String entity,
            String store,
            String actor,
            String batchNo,
            HandoverReq req,
            String key) {
        context(entity, store);
        id(actor);
        id(batchNo);
        key(key);
        if (req == null
                || req.kind() == null
                || !Set.of("DINE_IN", "PICKUP", "DELIVERY").contains(req.kind())) throw bad();
        validateCustodyLines(req.lines());
        reason("HOLD", req.reason());
        if ("DELIVERY".equals(req.kind())
                && (req.recipientNo() == null
                        || req.recipientNo().isBlank()
                        || req.recipientRef() == null
                        || req.recipientRef().isBlank())) throw bad();
        if (req.recipientNo() != null
                        && (req.recipientNo().isBlank() || req.recipientNo().length() > 64)
                || req.recipientRef() != null
                        && (req.recipientRef().isBlank() || req.recipientRef().length() > 128)) {
            throw bad();
        }
        var local =
                command(
                        entity,
                        store,
                        actor,
                        key,
                        "HANDOVER",
                        batchNo,
                        req,
                        HandoverVO.class,
                        () -> {
                            var batch = batch(entity, store, batchNo, true);
                            if (!req.kind().equals(batch.getFulfillment())) throw conflict();
                            var receipt =
                                    recordCustody(
                                            entity,
                                            store,
                                            actor,
                                            batchNo,
                                            req.lines(),
                                            req.reason(),
                                            req.recipientNo(),
                                            req.recipientRef(),
                                            false,
                                            key);
                            return new HandoverVO(
                                    receipt.operationNo(),
                                    batchNo,
                                    receipt.complete()
                                            ? "LOCAL_HANDOVER_RECORDED"
                                            : "PARTIAL_LOCAL_HANDOVER_RECORDED",
                                    true,
                                    null);
                        });
        if ("PICKUP".equals(req.kind())) return local;
        var batch =
                DataScopeContext.executeWithoutScope(() -> batch(entity, store, batchNo, false));
        var node = json.readTree(batch.getFrozenRequest()).get("inventoryPlan");
        if (node == null || node.isNull()) return local;
        if (fulfillment == null) throw conflict();
        var owner = fulfillment.getIfUnique();
        if (owner == null) throw conflict();
        var all = custodyReceipts(entity, store, batchNo);
        var selected =
                all.stream()
                        .filter(r -> r.operationNo().equals(local.operationNo()))
                        .findFirst()
                        .orElseThrow(KitchenTaskServiceImpl::conflict);
        var plan = json.treeToValue(node, InventoryDemandPlanPort.Plan.class);
        var reference =
                new IndustryFulfillmentPort.Reference(
                        new PricingSourceFactsPort.Scope("MAIN", entity, store),
                        new PricingSourceRef(
                                "FOOD",
                                "CART",
                                batch.getCartNo(),
                                batch.getCartVersion(),
                                null,
                                null),
                        selected.orderNo(),
                        selected.storeOrderNo());
        var known = owner.query(reference, actor, key);
        if (known.fulfillmentNo() != null && !known.pending())
            return new HandoverVO(
                    local.operationNo(), batchNo, known.status(), false, known.fulfillmentNo());
        if (known.fulfillmentNo() != null)
            return new HandoverVO(
                    local.operationNo(), batchNo, known.status(), true, known.fulfillmentNo());
        var original =
                DataScopeContext.executeWithoutScope(
                        () ->
                                lines.selectList(
                                        Wrappers.<FnbOrderLine>lambdaQuery()
                                                .eq(FnbOrderLine::getEntityNo, entity)
                                                .eq(FnbOrderLine::getBatchNo, batchNo)));
        var quantities = new HashMap<String, BigDecimal>();
        original.forEach(l -> quantities.put(l.getFoodLineNo(), l.getQty()));
        var targets = CustodyFulfillmentPolicy.targets(all, selected, quantities);
        var result =
                owner.fulfill(
                        new IndustryFulfillmentPort.Fulfill(
                                reference,
                                actor,
                                known.subOrderVersion(),
                                "DELIVERY".equals(req.kind()) ? "DELIVERY_HANDOVER" : "DINE_IN",
                                selected.operationNo(),
                                plan.planHash(),
                                null,
                                selected.recipientNo(),
                                selected.recipientRef(),
                                targets),
                        key);
        return new HandoverVO(
                local.operationNo(),
                batchNo,
                result.status(),
                result.pending(),
                result.fulfillmentNo());
    }

    @Override
    public List<CustodyReceiptVO> custodyReceipts(String entity, String store, String batchNo) {
        context(entity, store);
        id(batchNo);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    batch(entity, store, batchNo, false);
                    var found = new LinkedHashMap<String, CustodyReceiptVO>();
                    for (var ticket :
                            tickets.selectList(
                                    Wrappers.<FnbKitchenTicket>lambdaQuery()
                                            .eq(FnbKitchenTicket::getEntityNo, entity)
                                            .eq(FnbKitchenTicket::getBatchNo, batchNo)
                                            .eq(FnbKitchenTicket::getKind, "MAKE"))) {
                        for (var task : ticketTasks(entity, ticket.getTicketNo()))
                            for (var event :
                                    events.selectList(
                                            Wrappers.<FnbServiceEvent>lambdaQuery()
                                                    .eq(FnbServiceEvent::getEntityNo, entity)
                                                    .eq(
                                                            FnbServiceEvent::getTaskNo,
                                                            task.getTaskNo())
                                                    .isNotNull(FnbServiceEvent::getCustodySnapshot)
                                                    .orderByAsc(FnbServiceEvent::getId))) {
                                var receipt =
                                        json.readValue(
                                                event.getCustodySnapshot(), CustodyReceiptVO.class);
                                if (!entity.equals(receipt.entityNo())
                                        || !store.equals(receipt.storeNo())
                                        || !batchNo.equals(receipt.batchNo())) throw conflict();
                                found.putIfAbsent(receipt.operationNo(), receipt);
                            }
                    }
                    return found.values().stream()
                            .sorted(
                                    Comparator.comparing(CustodyReceiptVO::recordedAt)
                                            .thenComparing(CustodyReceiptVO::operationNo))
                            .toList();
                });
    }

    private CustodyReceiptVO recordCustody(
            String entity,
            String store,
            String actor,
            String batchNo,
            List<HandoverLine> requested,
            String reason,
            String recipient,
            String recipientRef,
            boolean packing,
            String key) {
        var batch = batch(entity, store, batchNo, true);
        if (!"ACCEPTED".equals(batch.getStatus()) || bindings == null || allocations == null)
            throw conflict();
        if (packing && !Set.of("PICKUP", "DELIVERY").contains(batch.getFulfillment()))
            throw conflict();
        var binding =
                bindings.selectOne(
                        Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                .eq(FnbCoreOrderBinding::getEntityNo, entity)
                                .eq(FnbCoreOrderBinding::getBatchNo, batchNo)
                                .eq(FnbCoreOrderBinding::getStatus, "BOUND"));
        if (binding == null) throw conflict();
        var original =
                tickets.selectOne(
                        Wrappers.<FnbKitchenTicket>lambdaQuery()
                                .eq(FnbKitchenTicket::getEntityNo, entity)
                                .eq(FnbKitchenTicket::getBatchNo, batchNo)
                                .eq(FnbKitchenTicket::getKind, "MAKE"));
        if (original == null) throw conflict();
        var originals = ticketTasks(entity, original.getTicketNo());
        var orderLines =
                lines.selectList(
                        Wrappers.<FnbOrderLine>lambdaQuery()
                                .eq(FnbOrderLine::getEntityNo, entity)
                                .eq(FnbOrderLine::getBatchNo, batchNo));
        var deltas = new HashMap<String, BigDecimal>();
        requested.forEach(l -> deltas.put(l.foodLineNo(), l.qty()));
        var anchors = new HashMap<String, FnbKitchenTask>();
        var sources = new ArrayList<CustodySourceLine>();
        boolean complete = true;
        int matched = 0;
        for (var line : orderLines) {
            BigDecimal cancelled = tickets.cancelledMainQty(entity, line.getFoodLineNo());
            BigDecimal demand =
                    line.getQty().subtract(cancelled == null ? BigDecimal.ZERO : cancelled);
            if (demand.signum() < 0) throw conflict();
            var lineTasks =
                    originals.stream()
                            .filter(t -> line.getFoodLineNo().equals(t.getFoodLineNo()))
                            .toList();
            if (demand.signum() == 0) {
                if (deltas.containsKey(line.getFoodLineNo())) throw conflict();
                continue;
            }
            var anchor =
                    lineTasks.stream()
                            .filter(
                                    t ->
                                            t.getComponentNo() == null
                                                    && "PACK".equals(t.getTaskRole()))
                            .findFirst()
                            .orElse(
                                    "DINE_IN".equals(batch.getFulfillment()) && !lineTasks.isEmpty()
                                            ? lineTasks.getFirst()
                                            : null);
            if (anchor == null) throw conflict();
            BigDecimal prior =
                    factQty(
                            entity,
                            anchor.getTaskNo(),
                            packing ? "PACK_CONFIRMED" : "CUSTODY_HANDOVER");
            BigDecimal delta = deltas.getOrDefault(line.getFoodLineNo(), BigDecimal.ZERO);
            if (prior.add(delta).compareTo(demand) > 0) throw conflict();
            if (prior.add(delta).compareTo(demand) < 0) complete = false;
            if (delta.signum() == 0) continue;
            matched++;
            anchors.put(line.getFoodLineNo(), anchor);
            // Legacy whole handover receipts cannot be silently reused for partial custody.
            for (var task : lineTasks)
                if (factQty(entity, task.getTaskNo(), "HANDOVER").signum() > 0) throw conflict();
            var candidates =
                    tasks.selectList(
                            Wrappers.<FnbKitchenTask>lambdaQuery()
                                    .eq(FnbKitchenTask::getEntityNo, entity)
                                    .eq(FnbKitchenTask::getFoodLineNo, line.getFoodLineNo()));
            if (!packing
                    && !"DINE_IN".equals(batch.getFulfillment())
                    && prior.add(delta)
                                    .compareTo(
                                            factQty(entity, anchor.getTaskNo(), "PACK_CONFIRMED"))
                            > 0) {
                throw conflict();
            }
            for (var task : lineTasks) {
                BigDecimal required =
                        KitchenReturnServiceImpl.quantity(
                                delta, task.getQty().subtract(task.getVoidQty()), demand);
                // Packing checks cumulative packed quantity against the still unhanded physical
                // output.
                BigDecimal checkQty =
                        packing
                                ? KitchenReturnServiceImpl.quantity(
                                        prior.add(delta)
                                                .subtract(
                                                        factQty(
                                                                entity,
                                                                anchor.getTaskNo(),
                                                                "CUSTODY_HANDOVER")),
                                        task.getQty().subtract(task.getVoidQty()),
                                        demand)
                                : required;
                if (checkQty.compareTo(availableForCustody(entity, task, candidates)) > 0)
                    throw conflict();
                if (!packing)
                    consumeCustody(entity, actor, task, candidates, required, reason, key);
            }
            var allocationRows =
                    allocations.selectList(
                            Wrappers.<FnbCoreLineAllocation>lambdaQuery()
                                    .eq(FnbCoreLineAllocation::getEntityNo, entity)
                                    .eq(
                                            FnbCoreLineAllocation::getFoodLineNo,
                                            line.getFoodLineNo()));
            if (allocationRows.isEmpty()) throw conflict();
            sources.add(
                    new CustodySourceLine(
                            line.getFoodLineNo(),
                            line.getSourceLineRef(),
                            allocationRows.stream()
                                    .map(
                                            a ->
                                                    new CoreLineScope(
                                                            a.getAllocationNo(),
                                                            a.getOrderItemId(),
                                                            a.getSourceComponentRef(),
                                                            a.getQty(),
                                                            a.getUnit(),
                                                            a.getCoreSnapshotHash(),
                                                            a.getVersion()))
                                    .toList(),
                            lineTasks.stream()
                                    .map(
                                            t ->
                                                    new KitchenTaskScope(
                                                            t.getTaskNo(),
                                                            t.getComponentNo(),
                                                            t.getStationNo(),
                                                            t.getTaskRole(),
                                                            t.getRouteVersion(),
                                                            t.getVersion()))
                                    .toList()));
        }
        if (matched != requested.size()) throw conflict();
        String action = packing ? "PACK_CONFIRMED" : "CUSTODY_HANDOVER";
        var receipt =
                new CustodyReceiptVO(
                        BizKey.next(packing ? "FPCK" : "FHOV"),
                        entity,
                        store,
                        batchNo,
                        binding.getPaymentRootNo(),
                        binding.getStoreOrderNo(),
                        binding.getBindingNo(),
                        binding.getVersion(),
                        batch.getVersion(),
                        FoodCommandFingerprint.hash("custody-source:v1", batch.getFrozenRequest()),
                        action,
                        batch.getFulfillment(),
                        requested,
                        sources,
                        recipient,
                        recipientRef,
                        actor,
                        Instant.now().toString(),
                        complete,
                        true);
        String snapshot = json.writeValueAsString(receipt);
        for (var line : requested) {
            var event =
                    append(
                            entity,
                            actor,
                            anchors.get(line.foodLineNo()).getTaskNo(),
                            action,
                            line.qty(),
                            reason,
                            FoodCommandFingerprint.hash(
                                    "custody-line:v1",
                                    entity,
                                    store,
                                    action,
                                    key,
                                    line.foodLineNo()));
            event.setCustodySnapshot(snapshot);
            if (events.updateById(event) != 1) throw conflict();
        }
        // Preserve the existing complete original pickup boundary only when original demand is
        // intact.
        // A complete net/cancelled custody receipt must never stand in for original full-item
        // verification.
        boolean intact = originals.stream().allMatch(t -> t.getVoidQty().signum() == 0);
        for (var line : orderLines) {
            var cancelled = tickets.cancelledMainQty(entity, line.getFoodLineNo());
            if (cancelled != null && cancelled.signum() != 0) intact = false;
        }
        if (!packing && complete && intact)
            for (var task : originals) {
                append(
                        entity,
                        actor,
                        task.getTaskNo(),
                        "HANDOVER",
                        task.getQty(),
                        reason,
                        FoodCommandFingerprint.hash(
                                "custody-complete:v1", entity, store, batchNo, task.getTaskNo()));
            }
        return receipt;
    }

    private BigDecimal factQty(String entity, String task, String action) {
        return events
                .selectList(
                        Wrappers.<FnbServiceEvent>lambdaQuery()
                                .eq(FnbServiceEvent::getEntityNo, entity)
                                .eq(FnbServiceEvent::getTaskNo, task)
                                .eq(FnbServiceEvent::getAction, action))
                .stream()
                .map(FnbServiceEvent::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal availableForCustody(
            String entity, FnbKitchenTask original, List<FnbKitchenTask> candidates) {
        BigDecimal ready =
                original.getReadyQty()
                        .subtract(readyLoss(entity, original.getTaskNo()))
                        .subtract(original.getServedQty());
        for (var remake : candidates)
            if (original.getTaskNo().equals(remake.getTaskPartKey())
                    && "REMAKE".equals(head(entity, remake.getTicketNo()).getKind())) {
                ready = ready.add(availableForCustody(entity, remake, candidates));
            }
        return ready;
    }

    private void consumeCustody(
            String entity,
            String actor,
            FnbKitchenTask original,
            List<FnbKitchenTask> candidates,
            BigDecimal amount,
            String reason,
            String key) {
        BigDecimal own =
                original.getReadyQty()
                        .subtract(readyLoss(entity, original.getTaskNo()))
                        .subtract(original.getServedQty())
                        .min(amount);
        if (own.signum() > 0) {
            KitchenQuantityPolicy.apply(
                    original,
                    "SERVE",
                    own,
                    loss(entity, original.getTaskNo()),
                    readyLoss(entity, original.getTaskNo()));
            stampUpdate(original, actor);
            if (tasks.updateById(original) != 1) throw conflict();
            append(
                    entity,
                    actor,
                    original.getTaskNo(),
                    "SERVE",
                    own,
                    reason,
                    FoodCommandFingerprint.hash(
                            "custody-serve:v1", entity, key, original.getTaskNo()));
            refreshTicket(entity, original.getTicketNo(), actor);
        }
        BigDecimal remaining = amount.subtract(own);
        for (var child : candidates)
            if (remaining.signum() > 0
                    && original.getTaskNo().equals(child.getTaskPartKey())
                    && "REMAKE".equals(head(entity, child.getTicketNo()).getKind())) {
                BigDecimal taking = remaining.min(availableForCustody(entity, child, candidates));
                if (taking.signum() > 0)
                    consumeCustody(entity, actor, child, candidates, taking, reason, key);
                remaining = remaining.subtract(taking);
            }
        if (remaining.signum() > 0) throw conflict();
    }

    private static void validateCustodyLines(List<HandoverLine> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 1000) throw bad();
        var seen = new HashSet<String>();
        for (var line : lines) {
            if (line == null) throw bad();
            id(line.foodLineNo());
            KitchenQuantityPolicy.positive(line.qty());
            if (!seen.add(line.foodLineNo())) throw bad();
        }
    }

    private void stageReady(String entity, FnbKitchenTask current, BigDecimal target) {
        int rank = rank(current.getTaskRole());
        if (rank == 0) return;
        var candidates =
                tasks.selectList(
                        Wrappers.<FnbKitchenTask>lambdaQuery()
                                .eq(FnbKitchenTask::getEntityNo, entity)
                                .eq(FnbKitchenTask::getFoodLineNo, current.getFoodLineNo()));
        for (var upstream : candidates) {
            if (!"MAKE".equals(head(entity, upstream.getTicketNo()).getKind())
                    || rank(upstream.getTaskRole()) >= rank) continue;
            BigDecimal ready = effectiveReady(entity, upstream, candidates);
            BigDecimal required = upstream.getQty().subtract(upstream.getVoidQty());
            if (required.signum() == 0) continue;
            if (ready.multiply(current.getQty().subtract(current.getVoidQty()))
                            .compareTo(target.multiply(required))
                    < 0) throw conflict();
        }
    }

    private BigDecimal effectiveReady(
            String entity, FnbKitchenTask original, List<FnbKitchenTask> candidates) {
        BigDecimal ready = original.getReadyQty().subtract(readyLoss(entity, original.getTaskNo()));
        for (var remake : candidates)
            if (original.getTaskNo().equals(remake.getTaskPartKey())
                    && "REMAKE".equals(head(entity, remake.getTicketNo()).getKind())) {
                ready = ready.add(effectiveReady(entity, remake, candidates));
            }
        return ready;
    }

    private static int rank(String role) {
        return switch (role) {
            case "MAKE" -> 0;
            case "ASSEMBLE" -> 1;
            case "PACK" -> 2;
            default -> throw conflict();
        };
    }

    private <T> T command(
            String entity,
            String store,
            String actor,
            String key,
            String kind,
            String target,
            Object req,
            Class<T> type,
            Supplier<T> body) {
        String scope = FoodCommandFingerprint.scope(kind, entity, store);
        String hash =
                FoodCommandFingerprint.hash(
                        "kitchen-command:v1", target, json.writeValueAsString(req));
        return tx(() -> idem.execute(key, scope, actor, hash, type, body));
    }

    private <T> T tx(Supplier<T> body) {
        try {
            return DataScopeContext.executeWithoutScope(() -> write.execute(status -> body.get()));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw conflict();
        }
    }

    private FnbKitchenTask scopedTask(String entity, String store, String number) {
        var task =
                tasks.selectOne(
                        Wrappers.<FnbKitchenTask>lambdaQuery()
                                .eq(FnbKitchenTask::getEntityNo, entity)
                                .eq(FnbKitchenTask::getTaskNo, number));
        if (task == null) throw missing();
        batch(entity, store, head(entity, task.getTicketNo()).getBatchNo(), false);
        return task;
    }

    private FnbKitchenTask lockedTask(String entity, String store, String number) {
        var original = scopedTask(entity, store, number);
        batch(entity, store, head(entity, original.getTicketNo()).getBatchNo(), true);
        var row =
                tasks.selectOne(
                        Wrappers.<FnbKitchenTask>lambdaQuery()
                                .eq(FnbKitchenTask::getEntityNo, entity)
                                .eq(FnbKitchenTask::getTaskNo, number)
                                .last("FOR UPDATE"));
        if (row == null) throw missing();
        var lineTasks =
                tasks.selectList(
                        Wrappers.<FnbKitchenTask>lambdaQuery()
                                .eq(FnbKitchenTask::getEntityNo, entity)
                                .eq(FnbKitchenTask::getFoodLineNo, row.getFoodLineNo()));
        for (var related : lineTasks)
            if (!events.selectList(
                            Wrappers.<FnbServiceEvent>lambdaQuery()
                                    .eq(FnbServiceEvent::getEntityNo, entity)
                                    .eq(FnbServiceEvent::getTaskNo, related.getTaskNo())
                                    .eq(FnbServiceEvent::getAction, "HANDOVER"))
                    .isEmpty()) throw conflict();
        return row;
    }

    private FnbOrderBatch batch(String entity, String store, String number, boolean lock) {
        var row =
                batches.selectOne(
                        Wrappers.<FnbOrderBatch>lambdaQuery()
                                .eq(FnbOrderBatch::getEntityNo, entity)
                                .eq(store != null, FnbOrderBatch::getStoreNo, store)
                                .eq(FnbOrderBatch::getBatchNo, number)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private FnbKitchenTicket head(String entity, String number) {
        var row =
                tickets.selectOne(
                        Wrappers.<FnbKitchenTicket>lambdaQuery()
                                .eq(FnbKitchenTicket::getEntityNo, entity)
                                .eq(FnbKitchenTicket::getTicketNo, number));
        if (row == null) throw missing();
        return row;
    }

    private FnbOrderLine line(String entity, String number) {
        var row =
                lines.selectOne(
                        Wrappers.<FnbOrderLine>lambdaQuery()
                                .eq(FnbOrderLine::getEntityNo, entity)
                                .eq(FnbOrderLine::getFoodLineNo, number));
        if (row == null) throw missing();
        return row;
    }

    private FnbCheck check(String entity, String store, String number, boolean lock) {
        var row =
                checks.selectOne(
                        Wrappers.<FnbCheck>lambdaQuery()
                                .eq(FnbCheck::getEntityNo, entity)
                                .eq(FnbCheck::getStoreNo, store)
                                .eq(FnbCheck::getCheckNo, number)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        if (!"OPEN".equals(row.getStatus())) throw conflict();
        return row;
    }

    private FnbCourse course(String entity, String number, boolean lock) {
        var row =
                courses.selectOne(
                        Wrappers.<FnbCourse>lambdaQuery()
                                .eq(FnbCourse::getEntityNo, entity)
                                .eq(FnbCourse::getCourseNo, number)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private List<FnbKitchenTask> ticketTasks(String entity, String ticket) {
        return tasks.selectList(
                Wrappers.<FnbKitchenTask>lambdaQuery()
                        .eq(FnbKitchenTask::getEntityNo, entity)
                        .eq(FnbKitchenTask::getTicketNo, ticket)
                        .orderByAsc(FnbKitchenTask::getTaskNo));
    }

    private BigDecimal loss(String entity, String task) {
        return events
                .selectList(
                        Wrappers.<FnbServiceEvent>lambdaQuery()
                                .eq(FnbServiceEvent::getEntityNo, entity)
                                .eq(FnbServiceEvent::getTaskNo, task)
                                .eq(FnbServiceEvent::getAction, "LOSS"))
                .stream()
                .map(FnbServiceEvent::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal readyLoss(String entity, String task) {
        return events
                .selectList(
                        Wrappers.<FnbServiceEvent>lambdaQuery()
                                .eq(FnbServiceEvent::getEntityNo, entity)
                                .eq(FnbServiceEvent::getTaskNo, task)
                                .eq(FnbServiceEvent::getAction, "LOSS"))
                .stream()
                .map(e -> e.getReadyLossQty() == null ? e.getQty() : e.getReadyLossQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private FnbServiceEvent append(
            String entity,
            String actor,
            String task,
            String action,
            BigDecimal qty,
            String reason,
            String operation) {
        var event = new FnbServiceEvent();
        stamp(event, actor);
        event.setEntityNo(entity);
        event.setEventNo(BizKey.next("FSEV"));
        event.setTaskNo(task);
        event.setAction(action);
        event.setQty(qty);
        if ("LOSS".equals(action)) {
            var original =
                    tasks.selectOne(
                            Wrappers.<FnbKitchenTask>lambdaQuery()
                                    .eq(FnbKitchenTask::getEntityNo, entity)
                                    .eq(FnbKitchenTask::getTaskNo, task));
            if (original == null) throw conflict();
            event.setReadyLossQty(
                    qty.min(
                            original.getReadyQty()
                                    .subtract(original.getServedQty())
                                    .subtract(readyLoss(entity, task))));
        }
        event.setOperationKey(operation);
        event.setReason(reason);
        events.insert(event);
        var row =
                tasks.selectOne(
                        Wrappers.<FnbKitchenTask>lambdaQuery()
                                .eq(FnbKitchenTask::getEntityNo, entity)
                                .eq(FnbKitchenTask::getTaskNo, task));
        if (row == null) throw conflict();
        var head = head(entity, row.getTicketNo());
        var batch = batch(entity, null, head.getBatchNo(), false);
        outbox.publish(
                new DiningServiceEvent(
                        event.getEventNo(),
                        entity,
                        batch.getStoreNo(),
                        batch.getBatchNo(),
                        head.getTicketNo(),
                        task,
                        row.getFoodLineNo(),
                        row.getComponentNo(),
                        row.getStationNo(),
                        row.getTaskRole(),
                        row.getRouteVersion(),
                        action,
                        qty,
                        reason,
                        actor,
                        Instant.now()),
                new PublishOptions("FOOD_KITCHEN_TASK:" + task, 1));
        return event;
    }

    private void refreshTicket(String entity, String number, String actor) {
        var rows = ticketTasks(entity, number);
        var ticket = head(entity, number);
        boolean closed =
                rows.stream().allMatch(t -> Set.of("SERVED", "VOIDED").contains(t.getStatus()));
        ticket.setStatus(
                closed
                        ? "CLOSED"
                        : rows.stream().anyMatch(t -> t.getMakingQty().signum() > 0)
                                ? "ACTIVE"
                                : "QUEUED");
        stampUpdate(ticket, actor);
        if (tickets.updateById(ticket) != 1) throw conflict();
    }

    private TicketVO ticket(String entity, FnbKitchenTicket row) {
        return new TicketVO(
                row.getTicketNo(),
                row.getBatchNo(),
                row.getKind(),
                row.getOriginalTicketNo(),
                row.getStatus(),
                ticketTasks(entity, row.getTicketNo()).stream()
                        .map(t -> view(t, row.getBatchNo()))
                        .toList());
    }

    private TaskVO view(FnbKitchenTask t, String batch) {
        var line = line(t.getEntityNo(), t.getFoodLineNo());
        var head = head(t.getEntityNo(), t.getTicketNo());
        return new TaskVO(
                t.getTaskNo(),
                t.getTicketNo(),
                batch,
                t.getFoodLineNo(),
                t.getComponentNo(),
                t.getStationNo(),
                t.getTaskRole(),
                t.getRouteVersion(),
                t.getQty(),
                t.getMakingQty(),
                t.getReadyQty(),
                t.getServedQty(),
                t.getVoidQty(),
                t.getStatus(),
                line.getSeatNo(),
                line.getCourseNo(),
                t.getVersion(),
                head.getKind(),
                head.getOriginalTicketNo());
    }

    private static EventVO event(FnbServiceEvent e) {
        return new EventVO(
                e.getEventNo(),
                e.getTaskNo(),
                e.getAction(),
                e.getQty(),
                e.getReason(),
                e.getInventoryDocNo());
    }

    private static CourseVO courseView(FnbCourse c) {
        return new CourseVO(
                c.getCourseNo(),
                c.getCheckNo(),
                c.getName(),
                c.getSequenceNo(),
                c.getStatus(),
                c.getVersion());
    }

    private static String operation(String entity, String store, String key) {
        return FoodCommandFingerprint.hash("kitchen-operation:v1", entity, store, key);
    }

    private static void stamp(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedBy(actor);
        row.setCreatedAt(LocalDateTime.now());
        stampUpdate(row, actor);
    }

    private static void stampUpdate(BaseEntity row, String actor) {
        row.setUpdatedBy(actor);
        row.setUpdatedAt(LocalDateTime.now());
    }

    private static void reason(String action, String value) {
        if (value != null && value.length() > 255) throw bad();
        if (Set.of("HOLD", "VOID", "LOSS", "REMAKE", "RETURN_DISPOSE").contains(action)
                && (value == null || value.isBlank())) throw bad();
    }

    private static void context(String entity, String store) {
        id(entity);
        id(store);
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) throw bad();
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw bad();
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
