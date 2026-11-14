package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO;
import ai.neargo.shop.food.dto.KitchenReturnContracts.ItemVO;
import ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.KitchenQuantityPolicy;
import ai.neargo.shop.food.service.KitchenReturnService;
import ai.neargo.shop.food.service.KitchenTaskService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Original batch locks serialize kitchen disposition with task progress; all local changes commit
 * atomically.
 */
public class KitchenReturnServiceImpl implements KitchenReturnService {
    private final ReturnMapper returns;
    private final ReturnItemMapper items;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final CoreLineAllocationMapper allocations;
    private final ActionLogMapper actions;
    private final KitchenTaskService kitchen;
    private final KitchenTicketMapper tickets;
    private final OrderComponentMapper components;
    private final ObjectMapper json;
    private final TransactionTemplate write;

    public KitchenReturnServiceImpl(
            ReturnMapper returns,
            ReturnItemMapper items,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            CoreLineAllocationMapper allocations,
            ActionLogMapper actions,
            KitchenTaskService kitchen,
            ObjectMapper json,
            PlatformTransactionManager manager,
            KitchenTicketMapper tickets,
            OrderComponentMapper components) {
        this.returns = returns;
        this.items = items;
        this.batches = batches;
        this.lines = lines;
        this.allocations = allocations;
        this.actions = actions;
        this.kitchen = kitchen;
        this.tickets = tickets;
        this.components = components;
        this.json = json;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public ReturnKitchenVO get(String entity, String store, String number) {
        id(entity);
        id(store);
        id(number);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var row = head(entity, store, number, false);
                    return new ReturnKitchenVO(
                            number,
                            row.getBatchNo(),
                            row.getKitchenStatus(),
                            row.getVersion(),
                            items(entity, number).stream()
                                    .map(
                                            i ->
                                                    new ItemVO(
                                                            i.getReturnItemNo(),
                                                            i.getFoodLineNo(),
                                                            i.getComponentNo(),
                                                            i.getQty(),
                                                            i.getKitchenDisposition()))
                                    .toList());
                });
    }

    @Override
    public HandledVO handle(
            String entity, String store, String actor, String number, HandleReq req, String key) {
        id(entity);
        id(store);
        id(actor);
        id(number);
        if (key == null
                || key.isBlank()
                || key.length() > 128
                || req == null
                || req.version() == null
                || req.version() < 0
                || req.version() == Long.MAX_VALUE
                || req.decisions() == null
                || req.decisions().isEmpty()
                || req.decisions().size() > 1000
                || req.reason() == null
                || req.reason().isBlank()
                || req.reason().length() > 255) throw bad();
        var desired = new HashMap<String, String>();
        for (var d : req.decisions()) {
            if (d == null) throw bad();
            id(d.returnItemNo());
            if (d.disposition() == null
                    || !Set.of("CANCEL", "LOSS", "KEEP", "COMP", "GIFT").contains(d.disposition())
                    || desired.put(d.returnItemNo(), d.disposition()) != null) throw bad();
        }
        String request =
                FoodCommandFingerprint.hash("return-kitchen-key:v1", entity, store, number, key);
        String hash =
                FoodCommandFingerprint.hash(
                        "return-kitchen-command:v1",
                        number,
                        actor,
                        req.version().toString(),
                        req.reason(),
                        json.writeValueAsString(new TreeMap<>(desired)));
        return DataScopeContext.executeWithoutScope(
                () ->
                        write.execute(
                                tx -> {
                                    var initial = head(entity, store, number, false);
                                    var batch =
                                            batches.selectOne(
                                                    Wrappers.<FnbOrderBatch>lambdaQuery()
                                                            .eq(FnbOrderBatch::getEntityNo, entity)
                                                            .eq(FnbOrderBatch::getStoreNo, store)
                                                            .eq(
                                                                    FnbOrderBatch::getBatchNo,
                                                                    initial.getBatchNo())
                                                            .last("FOR UPDATE"));
                                    if (batch == null) throw missing();
                                    var row = head(entity, store, number, true);
                                    var prior =
                                            actions.selectList(
                                                    Wrappers.<FnbActionLog>lambdaQuery()
                                                            .eq(FnbActionLog::getEntityNo, entity)
                                                            .eq(FnbActionLog::getStoreNo, store)
                                                            .eq(
                                                                    FnbActionLog::getObjectType,
                                                                    "RETURN_KITCHEN")
                                                            .eq(FnbActionLog::getObjectNo, number)
                                                            .eq(
                                                                    FnbActionLog::getRequestNo,
                                                                    request));
                                    if (!prior.isEmpty()) {
                                        var original = prior.getFirst();
                                        if (!actor.equals(original.getOperatorNo())
                                                || !hash.equals(original.getBeforeRef()))
                                            throw conflict();
                                        return new HandledVO(
                                                original.getActionNo(), number, "HANDLED");
                                    }
                                    if (!req.version().equals(row.getVersion())
                                            || !"PENDING".equals(row.getKitchenStatus())
                                            || Set.of("CANCELLED", "REJECTED", "CANCEL_PENDING")
                                                    .contains(row.getStatus())) throw conflict();
                                    var source = items(entity, number);
                                    if (source.isEmpty() || source.size() != desired.size())
                                        throw bad();
                                    var queue =
                                            kitchen.queue(entity, store, null, batch.getBatchNo());
                                    for (var item : source) {
                                        var decision = desired.get(item.getReturnItemNo());
                                        if (decision == null) throw bad();
                                        var line =
                                                lines.selectOne(
                                                        Wrappers.<FnbOrderLine>lambdaQuery()
                                                                .eq(
                                                                        FnbOrderLine::getEntityNo,
                                                                        entity)
                                                                .eq(
                                                                        FnbOrderLine::getBatchNo,
                                                                        batch.getBatchNo())
                                                                .eq(
                                                                        FnbOrderLine::getFoodLineNo,
                                                                        item.getFoodLineNo()));
                                        if (line == null) throw conflict();
                                        FnbOrderComponent returnedComponent = null;
                                        if (item.getComponentNo() != null) {
                                            returnedComponent =
                                                    components.selectOne(
                                                            Wrappers
                                                                    .<FnbOrderComponent>
                                                                            lambdaQuery()
                                                                    .eq(
                                                                            FnbOrderComponent
                                                                                    ::getEntityNo,
                                                                            entity)
                                                                    .eq(
                                                                            FnbOrderComponent
                                                                                    ::getFoodLineNo,
                                                                            line.getFoodLineNo())
                                                                    .eq(
                                                                            FnbOrderComponent
                                                                                    ::getComponentNo,
                                                                            item.getComponentNo()));
                                            if (returnedComponent == null) throw conflict();
                                        }
                                        final FnbOrderComponent selectedComponent =
                                                returnedComponent;
                                        BigDecimal originalQty =
                                                returnedComponent == null
                                                        ? line.getQty()
                                                        : returnedComponent.getQty();
                                        KitchenQuantityPolicy.positive(item.getQty());
                                        var allocation =
                                                allocations.selectOne(
                                                        Wrappers
                                                                .<FnbCoreLineAllocation>
                                                                        lambdaQuery()
                                                                .eq(
                                                                        FnbCoreLineAllocation
                                                                                ::getEntityNo,
                                                                        entity)
                                                                .eq(
                                                                        FnbCoreLineAllocation
                                                                                ::getAllocationNo,
                                                                        item
                                                                                .getCoreLineAllocationRef())
                                                                .eq(
                                                                        FnbCoreLineAllocation
                                                                                ::getFoodLineNo,
                                                                        item.getFoodLineNo()));
                                        if (allocation == null
                                                || item.getQty().compareTo(allocation.getQty()) > 0
                                                || allocation.getQty().compareTo(originalQty) != 0
                                                || selectedComponent != null
                                                        && !selectedComponent
                                                                .getSourceComponentRef()
                                                                .equals(
                                                                        allocation
                                                                                .getSourceComponentRef()))
                                            throw conflict();
                                        var targets =
                                                queue.stream()
                                                        .filter(
                                                                t ->
                                                                        "MAKE"
                                                                                        .equals(
                                                                                                t
                                                                                                        .ticketKind())
                                                                                && item.getFoodLineNo()
                                                                                        .equals(
                                                                                                t
                                                                                                        .foodLineNo())
                                                                                && (selectedComponent
                                                                                                == null
                                                                                        || selectedComponent
                                                                                                .getComponentNo()
                                                                                                .equals(
                                                                                                        t
                                                                                                                .componentNo())))
                                                        .toList();
                                        if ("CANCEL".equals(decision)
                                                && selectedComponent == null) {
                                            BigDecimal priorCancelled =
                                                    tickets.cancelledMainQty(
                                                            entity, line.getFoodLineNo());
                                            if (priorCancelled == null)
                                                priorCancelled = BigDecimal.ZERO;
                                            if (priorCancelled
                                                            .add(item.getQty())
                                                            .compareTo(line.getQty())
                                                    > 0) throw conflict();
                                        }
                                        if ("LOSS".equals(decision) && targets.isEmpty())
                                            throw conflict();
                                        // KEEP is an explicit operational choice, not a conclusion
                                        // drawn from the financial case.
                                        if (Set.of("CANCEL", "LOSS").contains(decision))
                                            for (var task : targets) {
                                                BigDecimal sourceQty = line.getQty();
                                                if (task.componentNo() != null) {
                                                    var component =
                                                            components.selectOne(
                                                                    Wrappers
                                                                            .<FnbOrderComponent>
                                                                                    lambdaQuery()
                                                                            .eq(
                                                                                    FnbOrderComponent
                                                                                            ::getEntityNo,
                                                                                    entity)
                                                                            .eq(
                                                                                    FnbOrderComponent
                                                                                            ::getFoodLineNo,
                                                                                    line
                                                                                            .getFoodLineNo())
                                                                            .eq(
                                                                                    FnbOrderComponent
                                                                                            ::getComponentNo,
                                                                                    task
                                                                                            .componentNo()));
                                                    if (component == null) throw conflict();
                                                    sourceQty = component.getQty();
                                                }
                                                BigDecimal quantity =
                                                        quantity(
                                                                item.getQty(),
                                                                sourceQty,
                                                                originalQty);
                                                kitchen.act(
                                                        entity,
                                                        store,
                                                        actor,
                                                        task.taskNo(),
                                                        new TaskActionReq(
                                                                task.version(),
                                                                "CANCEL".equals(decision)
                                                                        ? "VOID"
                                                                        : "RETURN_DISPOSE",
                                                                quantity,
                                                                req.reason()),
                                                        FoodCommandFingerprint.hash(
                                                                "return-task:v1",
                                                                request,
                                                                item.getReturnItemNo(),
                                                                task.taskNo()));
                                            }
                                        if (items.update(
                                                        null,
                                                        Wrappers.<FnbReturnItem>lambdaUpdate()
                                                                .eq(
                                                                        FnbReturnItem::getEntityNo,
                                                                        entity)
                                                                .eq(
                                                                        FnbReturnItem
                                                                                ::getReturnItemNo,
                                                                        item.getReturnItemNo())
                                                                .eq(
                                                                        FnbReturnItem::getVersion,
                                                                        item.getVersion())
                                                                .set(
                                                                        FnbReturnItem
                                                                                ::getKitchenDisposition,
                                                                        decision)
                                                                .set(
                                                                        FnbReturnItem::getUpdatedBy,
                                                                        actor)
                                                                .set(
                                                                        FnbReturnItem::getUpdatedAt,
                                                                        LocalDateTime.now())
                                                                .set(
                                                                        FnbReturnItem::getVersion,
                                                                        item.getVersion() + 1))
                                                != 1) throw conflict();
                                    }
                                    // Deliberately update only the kitchen columns, preserving the
                                    // financial owner's current state.
                                    if (returns.update(
                                                    null,
                                                    Wrappers.<FnbReturn>lambdaUpdate()
                                                            .eq(FnbReturn::getEntityNo, entity)
                                                            .eq(FnbReturn::getStoreNo, store)
                                                            .eq(FnbReturn::getReturnNo, number)
                                                            .eq(
                                                                    FnbReturn::getVersion,
                                                                    req.version())
                                                            .set(
                                                                    FnbReturn::getKitchenStatus,
                                                                    "HANDLED")
                                                            .set(FnbReturn::getUpdatedBy, actor)
                                                            .set(
                                                                    FnbReturn::getUpdatedAt,
                                                                    LocalDateTime.now())
                                                            .set(
                                                                    FnbReturn::getVersion,
                                                                    req.version() + 1))
                                            != 1) throw conflict();
                                    var audit = new FnbActionLog();
                                    audit.setTenantNo("MAIN");
                                    audit.setEntityNo(entity);
                                    audit.setStoreNo(store);
                                    audit.setActionNo(BizKey.next("FACT"));
                                    audit.setObjectType("RETURN_KITCHEN");
                                    audit.setObjectNo(number);
                                    audit.setAction("HANDLED");
                                    audit.setOperatorNo(actor);
                                    audit.setRequestNo(request);
                                    audit.setReason(req.reason());
                                    audit.setBeforeRef(hash);
                                    audit.setAfterRef(
                                            FoodCommandFingerprint.hash("HANDLED", number));
                                    audit.setCreatedBy(actor);
                                    audit.setUpdatedBy(actor);
                                    audit.setCreatedAt(LocalDateTime.now());
                                    audit.setUpdatedAt(LocalDateTime.now());
                                    audit.setVersion(0L);
                                    audit.setDeleted(0);
                                    actions.insert(audit);
                                    return new HandledVO(audit.getActionNo(), number, "HANDLED");
                                }));
    }

    public static BigDecimal quantity(BigDecimal returning, BigDecimal target, BigDecimal line) {
        KitchenQuantityPolicy.positive(returning);
        KitchenQuantityPolicy.positive(target);
        KitchenQuantityPolicy.positive(line);
        try {
            BigDecimal result =
                    returning.multiply(target).divide(line, 6, RoundingMode.UNNECESSARY);
            KitchenQuantityPolicy.positive(result);
            return result;
        } catch (ArithmeticException unsupportedPrecision) {
            throw conflict();
        }
    }

    private FnbReturn head(String entity, String store, String number, boolean lock) {
        var row =
                returns.selectOne(
                        Wrappers.<FnbReturn>lambdaQuery()
                                .eq(FnbReturn::getEntityNo, entity)
                                .eq(FnbReturn::getStoreNo, store)
                                .eq(FnbReturn::getReturnNo, number)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private List<FnbReturnItem> items(String entity, String number) {
        return items.selectList(
                Wrappers.<FnbReturnItem>lambdaQuery()
                        .eq(FnbReturnItem::getEntityNo, entity)
                        .eq(FnbReturnItem::getReturnNo, number)
                        .orderByAsc(FnbReturnItem::getReturnItemNo));
    }

    private static void id(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim()))
            throw bad();
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException missing() {
        return BizException.of(ErrorCode.NOT_FOUND);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
