package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.BusinessReturnContracts;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ComponentSource;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.Frozen;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ItemVO;
import ai.neargo.shop.food.dto.BusinessReturnContracts.Line;
import ai.neargo.shop.food.dto.BusinessReturnContracts.OwnerRead;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;
import ai.neargo.shop.food.dto.BusinessReturnContracts.SourceItem;
import ai.neargo.shop.food.dto.DiningPriceFacts.Unit;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.policy.BusinessReturnDocuments;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.KitchenQuantityPolicy;
import ai.neargo.shop.food.service.BusinessReturnService;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.spi.DiningOrderChanged;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Original batch locks conserve local return quantities; financial and inventory owners are never
 * invoked to write.
 */
public class BusinessReturnServiceImpl implements BusinessReturnService {
    @Autowired private CheckBusinessGuardService checkGuard;
    private OutboxPublisher outbox;

    @Autowired
    public void orderChangePublisher(@Qualifier("foodOutboxPublisher") OutboxPublisher publisher) {
        outbox = publisher;
    }

    private void changed(
            FnbReturn source, String action, String change, long version, List<String> foodLines) {
        if (outbox == null) return;
        outbox.publish(
                new DiningOrderChanged(
                        source.getEntityNo(),
                        source.getStoreNo(),
                        source.getCheckNo(),
                        source.getBatchNo(),
                        change,
                        action,
                        version,
                        foodLines),
                new PublishOptions("FOOD:BATCH:" + source.getBatchNo(), 1));
    }

    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final OrderLineMapper lines;
    private final CoreLineAllocationMapper allocations;
    private final OrderComponentMapper components;
    private final ReturnMapper returns;
    private final ReturnItemMapper items;
    private final ActionLogMapper actions;
    private final Supplier<IndustryOrderPort> orders;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;

    public BusinessReturnServiceImpl(
            OrderBatchMapper batches,
            CoreOrderBindingMapper bindings,
            OrderLineMapper lines,
            CoreLineAllocationMapper allocations,
            OrderComponentMapper components,
            ReturnMapper returns,
            ReturnItemMapper items,
            ActionLogMapper actions,
            Supplier<IndustryOrderPort> orders,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager manager) {
        this.batches = batches;
        this.bindings = bindings;
        this.lines = lines;
        this.allocations = allocations;
        this.components = components;
        this.returns = returns;
        this.items = items;
        this.actions = actions;
        this.orders = orders;
        this.json = json;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public ReturnVO create(
            String entity,
            String store,
            String actor,
            String batchNo,
            CreateReq request,
            String key) {
        outside();
        id(entity);
        id(store);
        id(actor);
        id(batchNo);
        key(key);
        var normalized = normalize(request);
        String requestKey =
                "BR1_"
                        + FoodCommandFingerprint.hash("business-return-key:v1", entity, store, key)
                                .substring(0, 60);
        String requestHash =
                FoodCommandFingerprint.hash(
                        BusinessReturnContracts.KIND,
                        entity,
                        store,
                        actor,
                        batchNo,
                        json.writeValueAsString(normalized));
        var prior = read(() -> byKey(entity, store, requestKey));
        if (prior != null) {
            replay(prior, actor, batchNo, requestHash);
            return get(entity, store, prior.getReturnNo());
        }
        var initial = read(() -> batch(entity, store, batchNo, false));
        eligible(initial);
        if (!normalized.batchVersion().equals(initial.getVersion())) throw conflict();
        var originalBinding = read(() -> binding(entity, batchNo));
        var owner = orders.get();
        if (owner == null) throw unsupported();
        var proof =
                owner.getForStore(
                        new Scope("MAIN", entity, store),
                        actor,
                        originalBinding.getPaymentRootNo());
        unpaid(initial, originalBinding, proof);
        var evidence = new OwnerRead(clock.instant().toString(), proof);
        FnbReturn created;
        try {
            created =
                    transaction(
                            () -> {
                                if (checkGuard != null)
                                    checkGuard.lockBatchChecks(entity, store, batchNo);
                                var batch = batch(entity, store, batchNo, true);
                                var existing = byKey(entity, store, requestKey);
                                if (existing != null) {
                                    replay(existing, actor, batchNo, requestHash);
                                    return existing;
                                }
                                eligible(batch);
                                if (!normalized.batchVersion().equals(batch.getVersion())
                                        || !Objects.equals(
                                                initial.getFrozenRequest(),
                                                batch.getFrozenRequest())) throw conflict();
                                var currentBinding = binding(entity, batchNo);
                                if (!Objects.equals(
                                                originalBinding.getVersion(),
                                                currentBinding.getVersion())
                                        || !Objects.equals(
                                                originalBinding.getBindingNo(),
                                                currentBinding.getBindingNo())) throw conflict();
                                unpaid(batch, currentBinding, proof);
                                var source = sourceItems(entity, batch, normalized, proof);
                                for (var item : source) capacity(entity, store, batchNo, item);
                                String sourceHash =
                                        FoodCommandFingerprint.hash(
                                                "business-return-source:v1",
                                                batch.getFrozenRequest(),
                                                json.writeValueAsString(currentBinding),
                                                json.writeValueAsString(source));
                                var frozen =
                                        new Frozen(
                                                BusinessReturnContracts.KIND,
                                                1,
                                                entity,
                                                store,
                                                batchNo,
                                                batch.getVersion(),
                                                batch.getCartNo(),
                                                batch.getCartVersion(),
                                                currentBinding.getBindingNo(),
                                                currentBinding.getVersion(),
                                                batch.getBuyerNo(),
                                                actor,
                                                normalized.reason(),
                                                sourceHash,
                                                evidence,
                                                source);
                                var row = new FnbReturn();
                                stamp(row, actor);
                                row.setEntityNo(entity);
                                row.setStoreNo(store);
                                row.setReturnNo(BizKey.next("FRET"));
                                row.setCheckNo(batch.getCheckNo());
                                row.setBatchNo(batchNo);
                                row.setBuyerNo(batch.getBuyerNo());
                                row.setRequestKey(requestKey);
                                row.setRequestHash(requestHash);
                                row.setRequestSnapshot(json.writeValueAsString(frozen));
                                row.setReason(normalized.reason());
                                row.setStatus("BUSINESS_REQUESTED");
                                row.setFinancialStatus("NOT_REQUESTED");
                                row.setKitchenStatus("PENDING");
                                if (returns.insert(row) != 1) throw conflict();
                                for (var selected : source) {
                                    var item = new FnbReturnItem();
                                    stamp(item, actor);
                                    item.setEntityNo(entity);
                                    item.setReturnItemNo(BizKey.next("FRTI"));
                                    item.setReturnNo(row.getReturnNo());
                                    item.setFoodLineNo(selected.foodLineNo());
                                    item.setQty(selected.qty());
                                    if (selected.components().size() == 1
                                            && selected.components().getFirst().componentNo()
                                                    != null
                                            && !"MAIN"
                                                    .equals(
                                                            selected.components()
                                                                    .getFirst()
                                                                    .kind()))
                                        item.setComponentNo(
                                                selected.components().getFirst().componentNo());
                                    item.setCoreLineAllocationRef(selected.mainAllocationNo());
                                    item.setKitchenDisposition("KEEP");
                                    if (items.insert(item) != 1) throw conflict();
                                }
                                if (checkGuard != null)
                                    checkGuard.invalidateBatchShares(
                                            entity, store, batchNo, actor, "RETURN_REQUEST");
                                changed(
                                        row,
                                        "RETURN_REQUEST",
                                        row.getReturnNo(),
                                        row.getVersion(),
                                        source.stream()
                                                .map(SourceItem::foodLineNo)
                                                .distinct()
                                                .toList());
                                return row;
                            });
        } catch (DuplicateKeyException concurrent) {
            created = read(() -> byKey(entity, store, requestKey));
            if (created == null) throw conflict();
            replay(created, actor, batchNo, requestHash);
        }
        return get(entity, store, created.getReturnNo());
    }

    @Override
    public ReturnVO get(String entity, String store, String number) {
        id(entity);
        id(store);
        id(number);
        return read(() -> view(head(entity, store, number, false)));
    }

    @Override
    public ReturnVO cancel(
            String entity,
            String store,
            String actor,
            String number,
            CancelReq request,
            String key) {
        id(entity);
        id(store);
        id(actor);
        id(number);
        key(key);
        if (request == null) throw bad();
        version(request.version());
        reason(request.reason());
        String operation =
                FoodCommandFingerprint.hash(
                        "business-return-cancel-key:v1", entity, store, number, key);
        String hash =
                FoodCommandFingerprint.hash(
                        "business-return-cancel:v1",
                        actor,
                        number,
                        json.writeValueAsString(request));
        return transaction(
                () -> {
                    var initial = head(entity, store, number, false);
                    BusinessReturnDocuments.read(initial, json);
                    batch(entity, store, initial.getBatchNo(), true);
                    var row = head(entity, store, number, true);
                    BusinessReturnDocuments.read(row, json);
                    var previous =
                            actions.selectList(
                                    Wrappers.<FnbActionLog>lambdaQuery()
                                            .eq(FnbActionLog::getTenantNo, "MAIN")
                                            .eq(FnbActionLog::getEntityNo, entity)
                                            .eq(FnbActionLog::getStoreNo, store)
                                            .eq(FnbActionLog::getObjectType, "BUSINESS_RETURN")
                                            .eq(FnbActionLog::getObjectNo, number)
                                            .eq(FnbActionLog::getRequestNo, operation));
                    if (!previous.isEmpty()) {
                        if (previous.size() != 1
                                || !actor.equals(previous.getFirst().getOperatorNo())
                                || !hash.equals(previous.getFirst().getBeforeRef()))
                            throw conflict();
                        return view(row);
                    }
                    if (!request.version().equals(row.getVersion())
                            || !"BUSINESS_REQUESTED".equals(row.getStatus())
                            || !"PENDING".equals(row.getKitchenStatus())
                            || row.getCoreCaseRef() != null
                            || !"NOT_REQUESTED".equals(row.getFinancialStatus())) throw conflict();
                    if (returns.update(
                                    null,
                                    Wrappers.<FnbReturn>lambdaUpdate()
                                            .eq(FnbReturn::getTenantNo, "MAIN")
                                            .eq(FnbReturn::getEntityNo, entity)
                                            .eq(FnbReturn::getStoreNo, store)
                                            .eq(FnbReturn::getReturnNo, number)
                                            .eq(FnbReturn::getVersion, request.version())
                                            .eq(FnbReturn::getStatus, "BUSINESS_REQUESTED")
                                            .eq(FnbReturn::getKitchenStatus, "PENDING")
                                            .eq(FnbReturn::getFinancialStatus, "NOT_REQUESTED")
                                            .isNull(FnbReturn::getCoreCaseRef)
                                            .set(FnbReturn::getStatus, "CANCELLED")
                                            .set(FnbReturn::getVersion, request.version() + 1)
                                            .set(FnbReturn::getUpdatedBy, actor)
                                            .set(FnbReturn::getUpdatedAt, now()))
                            != 1) throw conflict();
                    var audit = new FnbActionLog();
                    stamp(audit, actor);
                    audit.setEntityNo(entity);
                    audit.setStoreNo(store);
                    audit.setActionNo(BizKey.next("FACT"));
                    audit.setObjectType("BUSINESS_RETURN");
                    audit.setObjectNo(number);
                    audit.setAction("CANCEL");
                    audit.setOperatorNo(actor);
                    audit.setRequestNo(operation);
                    audit.setReason(request.reason());
                    audit.setBeforeRef(hash);
                    audit.setAfterRef(FoodCommandFingerprint.hash("CANCELLED", number));
                    if (actions.insert(audit) != 1) throw conflict();
                    changed(
                            row,
                            "RETURN_CANCEL",
                            operation,
                            Math.addExact(request.version(), 1),
                            items
                                    .selectList(
                                            Wrappers.<FnbReturnItem>lambdaQuery()
                                                    .eq(FnbReturnItem::getEntityNo, entity)
                                                    .eq(FnbReturnItem::getReturnNo, number))
                                    .stream()
                                    .map(FnbReturnItem::getFoodLineNo)
                                    .distinct()
                                    .toList());
                    return view(head(entity, store, number, false));
                });
    }

    private List<SourceItem> sourceItems(
            String entity, FnbOrderBatch batch, CreateReq request, Result proof) {
        try {
            return checkedSourceItems(entity, batch, request, proof);
        } catch (JacksonException malformed) {
            throw conflict();
        }
    }

    private List<SourceItem> checkedSourceItems(
            String entity, FnbOrderBatch batch, CreateReq request, Result proof) {
        var tree = json.readTree(batch.getFrozenRequest());
        var unitsNode = tree.path("facts").path("payload").path("units");
        if (!unitsNode.isArray() || unitsNode.size() == 0 || unitsNode.size() > 1000)
            throw unsupported();
        var frozenUnits = new HashMap<String, List<Unit>>();
        for (var node : unitsNode) {
            var unit = json.treeToValue(node, Unit.class);
            if (unit == null
                    || unit.sourceLineNo() == null
                    || unit.componentRef() == null
                    || !unit.componentRef().matches("[0-9a-f]{64}")
                    || unit.quantity() == null
                    || unit.kind() == null
                    || !(Set.of("MAIN", "ADDON").contains(unit.kind())
                            || unit.kind().startsWith("BUNDLE_"))) throw conflict();
            KitchenQuantityPolicy.positive(unit.quantity());
            frozenUnits
                    .computeIfAbsent(unit.sourceLineNo(), ignored -> new ArrayList<>())
                    .add(unit);
        }
        var proofUnits = new HashMap<String, IndustryOrderPort.Allocation>();
        for (var unit : proof.allocations())
            if (proofUnits.put(unit.sourceLineNo() + ":" + unit.componentRef(), unit) != null) {
                throw conflict();
            }
        var result = new ArrayList<SourceItem>();
        for (var requested : request.lines()) {
            var line =
                    lines.selectOne(
                            Wrappers.<FnbOrderLine>lambdaQuery()
                                    .eq(FnbOrderLine::getTenantNo, "MAIN")
                                    .eq(FnbOrderLine::getEntityNo, entity)
                                    .eq(FnbOrderLine::getBatchNo, batch.getBatchNo())
                                    .eq(FnbOrderLine::getFoodLineNo, requested.foodLineNo()));
            if (line == null) throw missing();
            KitchenQuantityPolicy.positive(line.getQty());
            if (requested.componentNo() == null && requested.qty().compareTo(line.getQty()) > 0
                    || line.getSourceLineRef() == null
                    || line.getVersion() == null) {
                throw conflict();
            }
            var units = frozenUnits.get(line.getSourceLineRef());
            if (units == null || units.isEmpty()) throw conflict();
            var mainUnits = units.stream().filter(u -> "MAIN".equals(u.kind())).toList();
            if (mainUnits.size() != 1
                    || !Objects.equals(line.getSkuNo(), mainUnits.getFirst().skuNo())
                    || mainUnits.getFirst().quantity().compareTo(line.getQty()) != 0)
                throw conflict();
            var original =
                    allocations.selectList(
                            Wrappers.<FnbCoreLineAllocation>lambdaQuery()
                                    .eq(FnbCoreLineAllocation::getTenantNo, "MAIN")
                                    .eq(FnbCoreLineAllocation::getEntityNo, entity)
                                    .eq(
                                            FnbCoreLineAllocation::getFoodLineNo,
                                            line.getFoodLineNo()));
            var parts =
                    components.selectList(
                            Wrappers.<FnbOrderComponent>lambdaQuery()
                                    .eq(FnbOrderComponent::getTenantNo, "MAIN")
                                    .eq(FnbOrderComponent::getEntityNo, entity)
                                    .eq(FnbOrderComponent::getFoodLineNo, line.getFoodLineNo()));
            if (original.size() != units.size() || units.size() > 1 && parts.size() != units.size())
                throw conflict();
            var partByRef = new HashMap<String, FnbOrderComponent>();
            for (var part : parts) {
                var selected = json.readValue(part.getSelectionSnapshot(), Unit.class);
                if (selected == null
                        || !line.getSourceLineRef().equals(selected.sourceLineNo())
                        || selected.componentRef() == null
                        || selected.quantity() == null
                        || part.getQty() == null
                        || part.getQty().compareTo(selected.quantity()) != 0
                        || !Objects.equals(part.getSkuNo(), selected.skuNo())
                        || part.getVersion() == null
                        || partByRef.put(selected.componentRef(), part) != null) throw conflict();
                var expected =
                        units.stream()
                                .filter(u -> selected.componentRef().equals(u.componentRef()))
                                .findFirst()
                                .orElseThrow(BusinessReturnServiceImpl::conflict);
                if (!Objects.equals(expected.kind(), selected.kind())
                        || !Objects.equals(expected.skuNo(), selected.skuNo())
                        || expected.quantity().compareTo(selected.quantity()) != 0
                        || part.getSourceComponentRef() != null
                                && !selected.componentRef().equals(part.getSourceComponentRef())
                        || part.getComponentKind() != null
                                && !Objects.equals(selected.kind(), part.getComponentKind())) {
                    throw conflict();
                }
            }
            var byRef = new HashMap<String, FnbCoreLineAllocation>();
            for (var allocation : original)
                if (byRef.put(allocation.getSourceComponentRef(), allocation) != null)
                    throw conflict();
            var source = new ArrayList<ComponentSource>();
            FnbCoreLineAllocation main = null;
            var seen = new HashSet<String>();
            for (var unit : units) {
                if (!seen.add(unit.componentRef())) throw conflict();
                var allocation = byRef.get(unit.componentRef());
                var core = proofUnits.get(line.getSourceLineRef() + ":" + unit.componentRef());
                if (allocation == null
                        || core == null
                        || allocation.getQty() == null
                        || allocation.getVersion() == null
                        || allocation.getQty().compareTo(unit.quantity()) != 0
                        || allocation.getOrderItemId() == null
                        || allocation.getOrderItemId() != core.orderItemId()
                        || core.qty() <= 0
                        || allocation.getQty().compareTo(core.saleQuantity()) != 0
                        || !Objects.equals(allocation.getCoreSnapshotHash(), core.hash())
                        || allocation.getCoreSnapshotHash() == null
                        || !allocation.getCoreSnapshotHash().matches("[0-9a-f]{64}")
                        || !Objects.equals(allocation.getCurrency(), proof.currency()))
                    throw conflict();
                var part = partByRef.get(unit.componentRef());
                if (!parts.isEmpty()
                        && (part == null || part.getQty().compareTo(unit.quantity()) != 0))
                    throw conflict();
                if ("MAIN".equals(unit.kind())) main = allocation;
                // Every component delta must remain representable by the original kitchen quantity
                // contract.
                if (requested.componentNo() == null)
                    KitchenReturnServiceImpl.quantity(
                            requested.qty(), allocation.getQty(), line.getQty());
                source.add(
                        new ComponentSource(
                                unit.componentRef(),
                                unit.kind(),
                                allocation.getAllocationNo(),
                                allocation.getOrderItemId(),
                                allocation.getQty(),
                                allocation.getUnit(),
                                allocation.getCoreSnapshotHash(),
                                allocation.getVersion(),
                                part == null ? null : part.getComponentNo(),
                                part == null ? null : part.getVersion()));
            }
            if (main == null
                    || main.getQty().compareTo(line.getQty()) != 0
                    || !seen.containsAll(partByRef.keySet())) throw conflict();
            if (requested.componentNo() != null) {
                var selected =
                        source.stream()
                                .filter(c -> requested.componentNo().equals(c.componentNo()))
                                .findFirst()
                                .orElseThrow(BusinessReturnServiceImpl::conflict);
                if ("MAIN".equals(selected.kind())
                        || requested.qty().compareTo(selected.originalQty()) > 0) throw conflict();
                result.add(
                        new SourceItem(
                                line.getFoodLineNo(),
                                line.getSourceLineRef(),
                                selected.allocationNo(),
                                selected.orderItemId(),
                                requested.qty(),
                                selected.originalQty(),
                                line.getVersion(),
                                List.of(selected)));
            } else
                result.add(
                        new SourceItem(
                                line.getFoodLineNo(),
                                line.getSourceLineRef(),
                                main.getAllocationNo(),
                                main.getOrderItemId(),
                                requested.qty(),
                                line.getQty(),
                                line.getVersion(),
                                source.stream()
                                        .sorted(
                                                Comparator.comparing(
                                                        ComponentSource::sourceComponentRef))
                                        .toList()));
        }
        return List.copyOf(result);
    }

    private void capacity(String entity, String store, String batch, SourceItem source) {
        var previous =
                items.selectList(
                        Wrappers.<FnbReturnItem>lambdaQuery()
                                .eq(FnbReturnItem::getTenantNo, "MAIN")
                                .eq(FnbReturnItem::getEntityNo, entity)
                                .eq(FnbReturnItem::getFoodLineNo, source.foodLineNo())
                                .last("LIMIT 10001"));
        if (previous.size() > 10000) throw conflict();
        var reserved = new HashMap<String, BigDecimal>();
        for (var item : previous) {
            var prior =
                    returns.selectOne(
                            Wrappers.<FnbReturn>lambdaQuery()
                                    .eq(FnbReturn::getTenantNo, "MAIN")
                                    .eq(FnbReturn::getEntityNo, entity)
                                    .eq(FnbReturn::getReturnNo, item.getReturnNo()));
            if (prior == null
                    || !store.equals(prior.getStoreNo())
                    || !batch.equals(prior.getBatchNo())) throw conflict();
            if (Set.of("CANCELLED", "REJECTED")
                            .contains(Objects.requireNonNullElse(prior.getStatus(), ""))
                    && "PENDING".equals(prior.getKitchenStatus())
                    && Set.of("NONE", "NOT_REQUESTED")
                            .contains(Objects.requireNonNullElse(prior.getFinancialStatus(), "")))
                continue;
            var frozen = BusinessReturnDocuments.read(prior, json);
            var claimed =
                    frozen.items().stream()
                            .filter(i -> i.foodLineNo().equals(source.foodLineNo()))
                            .findFirst()
                            .orElseThrow(BusinessReturnServiceImpl::conflict);
            for (var component : claimed.components())
                reserved.merge(
                        component.sourceComponentRef(),
                        KitchenReturnServiceImpl.quantity(
                                claimed.qty(), component.originalQty(), claimed.originalQty()),
                        BigDecimal::add);
        }
        for (var component : source.components()) {
            var requested =
                    KitchenReturnServiceImpl.quantity(
                            source.qty(), component.originalQty(), source.originalQty());
            if (reserved.getOrDefault(component.sourceComponentRef(), BigDecimal.ZERO)
                            .add(requested)
                            .compareTo(component.originalQty())
                    > 0) throw conflict();
        }
    }

    private ReturnVO view(FnbReturn row) {
        var frozen = BusinessReturnDocuments.read(row, json);
        var source = new HashMap<String, SourceItem>();
        frozen.items().forEach(i -> source.put(i.foodLineNo(), i));
        var detail =
                items.selectList(
                        Wrappers.<FnbReturnItem>lambdaQuery()
                                .eq(FnbReturnItem::getTenantNo, "MAIN")
                                .eq(FnbReturnItem::getEntityNo, row.getEntityNo())
                                .eq(FnbReturnItem::getReturnNo, row.getReturnNo())
                                .orderByAsc(FnbReturnItem::getReturnItemNo));
        if (detail.size() != source.size()) throw conflict();
        var seen = new HashSet<String>();
        var output = new ArrayList<ItemVO>();
        for (var item : detail) {
            var original = source.get(item.getFoodLineNo());
            if (original == null
                    || !seen.add(item.getFoodLineNo())
                    || item.getQty() == null
                    || item.getQty().compareTo(original.qty()) != 0
                    || !Objects.equals(
                            item.getCoreLineAllocationRef(), original.mainAllocationNo()))
                throw conflict();
            output.add(
                    new ItemVO(
                            item.getReturnItemNo(),
                            item.getFoodLineNo(),
                            original.sourceLineRef(),
                            item.getQty(),
                            item.getKitchenDisposition(),
                            item.getCoreLineAllocationRef()));
        }
        return new ReturnVO(
                row.getReturnNo(),
                row.getBatchNo(),
                row.getStoreNo(),
                row.getVersion(),
                row.getStatus(),
                row.getFinancialStatus(),
                row.getKitchenStatus(),
                row.getCoreCaseRef(),
                false,
                "APPROVED".equals(row.getStatus()),
                row.getReason(),
                frozen.actorNo(),
                output,
                frozen);
    }

    private FnbOrderBatch batch(String entity, String store, String number, boolean lock) {
        var row =
                batches.selectOne(
                        Wrappers.<FnbOrderBatch>lambdaQuery()
                                .eq(FnbOrderBatch::getTenantNo, "MAIN")
                                .eq(FnbOrderBatch::getEntityNo, entity)
                                .eq(FnbOrderBatch::getStoreNo, store)
                                .eq(FnbOrderBatch::getBatchNo, number)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private FnbCoreOrderBinding binding(String entity, String batch) {
        var row =
                bindings.selectOne(
                        Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                .eq(FnbCoreOrderBinding::getTenantNo, "MAIN")
                                .eq(FnbCoreOrderBinding::getEntityNo, entity)
                                .eq(FnbCoreOrderBinding::getBatchNo, batch)
                                .eq(FnbCoreOrderBinding::getStatus, "BOUND"));
        if (row == null
                || row.getPaymentRootNo() == null
                || row.getStoreOrderNo() == null
                || row.getVersion() == null) throw conflict();
        return row;
    }

    private FnbReturn head(String entity, String store, String number, boolean lock) {
        var row =
                returns.selectOne(
                        Wrappers.<FnbReturn>lambdaQuery()
                                .eq(FnbReturn::getTenantNo, "MAIN")
                                .eq(FnbReturn::getEntityNo, entity)
                                .eq(FnbReturn::getStoreNo, store)
                                .eq(FnbReturn::getReturnNo, number)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private FnbReturn byKey(String entity, String store, String key) {
        return returns.selectOne(
                Wrappers.<FnbReturn>lambdaQuery()
                        .eq(FnbReturn::getTenantNo, "MAIN")
                        .eq(FnbReturn::getEntityNo, entity)
                        .eq(FnbReturn::getStoreNo, store)
                        .eq(FnbReturn::getRequestKey, key));
    }

    private static void eligible(FnbOrderBatch row) {
        if (!"ACCEPTED".equals(row.getStatus())
                || !"POSTPAY".equals(row.getPayMode())
                || row.getAcceptedAt() == null
                || row.getCartVersion() == null
                || row.getCartNo() == null
                || row.getBuyerNo() == null
                || row.getFrozenRequest() == null) {
            throw unsupported();
        }
    }

    private static void unpaid(FnbOrderBatch batch, FnbCoreOrderBinding binding, Result proof) {
        if (proof == null
                || !"WAIT_PAY".equals(proof.status())
                || !"POSTPAY".equals(proof.orderMode())
                || proof.currency() == null
                || !proof.currency().matches("[A-Z]{3}")
                || proof.inventoryLifecycleVersion() != 3
                || !proof.acceptedUnpaid()
                || !binding.getPaymentRootNo().equals(proof.orderNo())
                || !binding.getStoreOrderNo().equals(proof.subOrderNo())
                || !Objects.equals(batch.getQuoteRef(), proof.quoteNo())
                || batch.getCartVersion() != proof.sourceVersion()) {
            throw unsupported();
        }
    }

    private void replay(FnbReturn row, String actor, String batch, String hash) {
        BusinessReturnDocuments.read(row, json);
        if (!actor.equals(row.getCreatedBy())
                || !batch.equals(row.getBatchNo())
                || !hash.equals(row.getRequestHash())) throw conflict();
    }

    private static CreateReq normalize(CreateReq request) {
        if (request == null) throw bad();
        version(request.batchVersion());
        reason(request.reason());
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 256)
            throw bad();
        var seen = new HashSet<String>();
        var lines = new ArrayList<Line>();
        for (var line : request.lines()) {
            if (line == null) throw bad();
            id(line.foodLineNo());
            KitchenQuantityPolicy.positive(line.qty());
            if (!seen.add(line.foodLineNo())) throw bad();
            if (line.componentNo() != null) id(line.componentNo());
            lines.add(
                    new Line(
                            line.foodLineNo(),
                            new BigDecimal(line.qty().stripTrailingZeros().toPlainString()),
                            line.componentNo()));
        }
        return new CreateReq(
                request.batchVersion(),
                lines.stream().sorted(Comparator.comparing(Line::foodLineNo)).toList(),
                request.reason());
    }

    private <T> T transaction(Supplier<T> body) {
        try {
            return read(() -> write.execute(status -> body.get()));
        } catch (ConcurrencyFailureException failure) {
            throw conflict();
        }
    }

    private static <T> T read(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(body);
    }

    private void stamp(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now());
        row.setUpdatedAt(row.getCreatedAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Original unpaid source read must run outside a FOOD transaction");
        }
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) throw bad();
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw bad();
    }

    private static void version(Long value) {
        if (value == null || value < 0 || value == Long.MAX_VALUE) throw bad();
    }

    private static void reason(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 255
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) throw bad();
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

    private static BizException unsupported() {
        return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}
