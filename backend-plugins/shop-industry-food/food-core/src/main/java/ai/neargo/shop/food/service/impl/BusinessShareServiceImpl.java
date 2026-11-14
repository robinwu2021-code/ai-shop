package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessShareContracts.AllocationSource;
import ai.neargo.shop.food.dto.BusinessShareContracts.BatchSource;
import ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.Division;
import ai.neargo.shop.food.dto.BusinessShareContracts.LineWeight;
import ai.neargo.shop.food.dto.BusinessShareContracts.Person;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;
import ai.neargo.shop.food.dto.BusinessShareContracts.SourceSnapshot;
import ai.neargo.shop.food.dto.DiningPriceFacts.Unit;
import ai.neargo.shop.food.entity.FnbBusinessSharePlan;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.mapper.BusinessSharePlanMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.policy.BusinessSharePolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Local plans conserve immutable original amounts; every read rechecks local sources, never
 * financial balances.
 */
public class BusinessShareServiceImpl implements BusinessShareService {
    private CheckBusinessGuardService businessGuard;

    @Autowired(required = false)
    public void setBusinessGuard(CheckBusinessGuardService guard) {
        this.businessGuard = guard;
    }

    private Supplier<IndustryOrderPort> orderSources;
    private final ThreadLocal<Map<String, Result>> effectiveSources = new ThreadLocal<>();

    @Autowired(required = false)
    public void setOrderSources(ObjectProvider<IndustryOrderPort> owners) {
        orderSources = owners::getIfAvailable;
    }

    private static final String DRAFT = "DRAFT_BUSINESS_ONLY";
    private static final String CONFIRMED = "CONFIRMED_BUSINESS_ONLY";
    private static final String CANCELLED = "CANCELLED_BUSINESS_ONLY";
    private final BusinessSharePlanMapper plans;
    private final CheckMapper checks;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final CoreOrderBindingMapper bindings;
    private final CoreLineAllocationMapper allocations;
    private final IdempotencyService idem;
    private final ObjectMapper json;
    private final TransactionTemplate write;

    public BusinessShareServiceImpl(
            BusinessSharePlanMapper plans,
            CheckMapper checks,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations,
            IdempotencyService idem,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.plans = plans;
        this.checks = checks;
        this.batches = batches;
        this.lines = lines;
        this.bindings = bindings;
        this.allocations = allocations;
        this.idem = idem;
        this.json = json;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public PlanVO create(
            String entity,
            String store,
            String actor,
            String check,
            CreateReq request,
            String key) {
        return effective(
                entity, store, check, () -> createLocal(entity, store, actor, check, request, key));
    }

    private PlanVO createLocal(
            String entity,
            String store,
            String actor,
            String check,
            CreateReq request,
            String key) {
        context(entity, store, check);
        id(actor);
        key(key);
        if (request == null) throw bad();
        version(request.checkVersion());
        reason(request.reason());
        String requestKey =
                FoodCommandFingerprint.hash("business-share-create-key:v1", entity, store, key);
        String requestHash =
                FoodCommandFingerprint.hash(
                        "business-share-command:v1", check, json.writeValueAsString(request));
        var reference =
                command(
                        entity,
                        store,
                        actor,
                        check,
                        key,
                        "CREATE",
                        request,
                        () -> {
                            var header = check(entity, store, check);
                            var previous =
                                    plans.selectOne(
                                            Wrappers.<FnbBusinessSharePlan>lambdaQuery()
                                                    .eq(FnbBusinessSharePlan::getTenantNo, "MAIN")
                                                    .eq(FnbBusinessSharePlan::getEntityNo, entity)
                                                    .eq(FnbBusinessSharePlan::getStoreNo, store)
                                                    .eq(
                                                            FnbBusinessSharePlan::getRequestKey,
                                                            requestKey));
                            if (previous != null) {
                                if (!requestHash.equals(previous.getRequestHash())
                                        || !check.equals(previous.getCheckNo())
                                        || !actor.equals(previous.getCreatedBy())) throw conflict();
                                return new PlanReference(previous.getPlanNo());
                            }
                            if (!request.checkVersion().equals(header.getVersion()))
                                throw conflict();
                            if (businessGuard != null)
                                businessGuard.assertMutable(entity, store, check);
                            var source = source(entity, store, header);
                            var division =
                                    BusinessSharePolicy.divide(source.allocations(), request);
                            var active =
                                    plans.selectOne(
                                            Wrappers.<FnbBusinessSharePlan>lambdaQuery()
                                                    .eq(FnbBusinessSharePlan::getTenantNo, "MAIN")
                                                    .eq(FnbBusinessSharePlan::getEntityNo, entity)
                                                    .eq(FnbBusinessSharePlan::getStoreNo, store)
                                                    .eq(
                                                            FnbBusinessSharePlan::getActiveCheckNo,
                                                            check));
                            if (active != null) throw conflict();
                            var definition = canonical(request);
                            var row = new FnbBusinessSharePlan();
                            row.setEntityNo(entity);
                            row.setStoreNo(store);
                            row.setCheckNo(check);
                            row.setPlanNo(BizKey.next("FBSP"));
                            row.setActiveCheckNo(check);
                            row.setStatus(DRAFT);
                            row.setRequestKey(requestKey);
                            row.setRequestHash(requestHash);
                            row.setMode(request.mode());
                            row.setCurrency(source.currency());
                            row.setOriginalAmountMinor(source.originalAmountMinor());
                            row.setEffectiveAmountMinor(source.effectiveAmountMinor());
                            row.setSourceSnapshot(json.writeValueAsString(source));
                            row.setSourceHash(sourceHash(source));
                            row.setDivisionSnapshot(json.writeValueAsString(division));
                            row.setDefinitionSnapshot(json.writeValueAsString(definition));
                            row.setPlanHash(
                                    FoodCommandFingerprint.hash(
                                            "business-share-plan:v1",
                                            row.getSourceHash(),
                                            row.getDefinitionSnapshot(),
                                            row.getDivisionSnapshot()));
                            row.setReason(request.reason());
                            stamp(row, actor);
                            if (plans.insert(row) != 1) throw conflict();
                            return new PlanReference(row.getPlanNo());
                        });
        // Replays fetch the current audited plan and recheck local source consistency.
        return get(entity, store, check, reference.planNo());
    }

    @Override
    public PlanVO get(String entity, String store, String check, String plan) {
        return effective(entity, store, check, () -> getLocal(entity, store, check, plan));
    }

    private PlanVO getLocal(String entity, String store, String check, String plan) {
        context(entity, store, check);
        id(plan);
        return transaction(
                () -> {
                    var header = check(entity, store, check);
                    var row = plan(entity, store, check, plan);
                    boolean current = false;
                    try {
                        current =
                                row.getSourceHash()
                                        .equals(sourceHash(source(entity, store, header)));
                    } catch (BizException invalidSource) {
                        if (invalidSource.errorCode() != ErrorCode.CONFLICT) throw invalidSource;
                    }
                    return view(row, current);
                });
    }

    @Override
    public PlanVO confirm(
            String entity,
            String store,
            String actor,
            String check,
            String plan,
            ConfirmReq request,
            String key) {
        return effective(
                entity,
                store,
                check,
                () -> confirmLocal(entity, store, actor, check, plan, request, key));
    }

    private PlanVO confirmLocal(
            String entity,
            String store,
            String actor,
            String check,
            String plan,
            ConfirmReq request,
            String key) {
        context(entity, store, check);
        id(actor);
        id(plan);
        key(key);
        if (request == null) throw bad();
        version(request.version());
        hash(request.sourceHash());
        hash(request.planHash());
        var reference =
                command(
                        entity,
                        store,
                        actor,
                        check,
                        key,
                        "CONFIRM:" + plan,
                        request,
                        () -> {
                            var header = check(entity, store, check);
                            var row = plan(entity, store, check, plan);
                            if (businessGuard != null)
                                businessGuard.assertMutable(entity, store, check);
                            if (!request.version().equals(row.getVersion())
                                    || !request.sourceHash().equals(row.getSourceHash())
                                    || !request.planHash().equals(row.getPlanHash())
                                    || !DRAFT.equals(row.getStatus())
                                    || !row.getSourceHash()
                                            .equals(sourceHash(source(entity, store, header))))
                                throw conflict();
                            if (plans.update(
                                            null,
                                            Wrappers.<FnbBusinessSharePlan>lambdaUpdate()
                                                    .eq(FnbBusinessSharePlan::getTenantNo, "MAIN")
                                                    .eq(FnbBusinessSharePlan::getEntityNo, entity)
                                                    .eq(FnbBusinessSharePlan::getStoreNo, store)
                                                    .eq(FnbBusinessSharePlan::getPlanNo, plan)
                                                    .eq(
                                                            FnbBusinessSharePlan::getVersion,
                                                            request.version())
                                                    .eq(FnbBusinessSharePlan::getStatus, DRAFT)
                                                    .set(FnbBusinessSharePlan::getStatus, CONFIRMED)
                                                    .set(
                                                            FnbBusinessSharePlan::getConfirmedBy,
                                                            actor)
                                                    .set(
                                                            FnbBusinessSharePlan::getVersion,
                                                            request.version() + 1)
                                                    .set(FnbBusinessSharePlan::getUpdatedBy, actor)
                                                    .set(
                                                            FnbBusinessSharePlan::getUpdatedAt,
                                                            LocalDateTime.now()))
                                    != 1) throw conflict();
                            return new PlanReference(plan);
                        });
        return get(entity, store, check, reference.planNo());
    }

    @Override
    public PlanVO cancel(
            String entity,
            String store,
            String actor,
            String check,
            String plan,
            CancelReq request,
            String key) {
        return effective(
                entity,
                store,
                check,
                () -> cancelLocal(entity, store, actor, check, plan, request, key));
    }

    private PlanVO cancelLocal(
            String entity,
            String store,
            String actor,
            String check,
            String plan,
            CancelReq request,
            String key) {
        context(entity, store, check);
        id(actor);
        id(plan);
        key(key);
        if (request == null) throw bad();
        version(request.version());
        reason(request.reason());
        var reference =
                command(
                        entity,
                        store,
                        actor,
                        check,
                        key,
                        "CANCEL:" + plan,
                        request,
                        () -> {
                            check(entity, store, check);
                            var row = plan(entity, store, check, plan);
                            if (!request.version().equals(row.getVersion())
                                    || !Set.of(DRAFT, CONFIRMED).contains(row.getStatus())) {
                                throw conflict();
                            }
                            if (plans.update(
                                            null,
                                            Wrappers.<FnbBusinessSharePlan>lambdaUpdate()
                                                    .eq(FnbBusinessSharePlan::getTenantNo, "MAIN")
                                                    .eq(FnbBusinessSharePlan::getEntityNo, entity)
                                                    .eq(FnbBusinessSharePlan::getStoreNo, store)
                                                    .eq(FnbBusinessSharePlan::getPlanNo, plan)
                                                    .eq(
                                                            FnbBusinessSharePlan::getVersion,
                                                            request.version())
                                                    .in(
                                                            FnbBusinessSharePlan::getStatus,
                                                            DRAFT,
                                                            CONFIRMED)
                                                    .set(FnbBusinessSharePlan::getStatus, CANCELLED)
                                                    .set(
                                                            FnbBusinessSharePlan::getActiveCheckNo,
                                                            null)
                                                    .set(
                                                            FnbBusinessSharePlan::getCancelledBy,
                                                            actor)
                                                    .set(
                                                            FnbBusinessSharePlan
                                                                    ::getCancellationReason,
                                                            request.reason())
                                                    .set(
                                                            FnbBusinessSharePlan::getVersion,
                                                            request.version() + 1)
                                                    .set(FnbBusinessSharePlan::getUpdatedBy, actor)
                                                    .set(
                                                            FnbBusinessSharePlan::getUpdatedAt,
                                                            LocalDateTime.now()))
                                    != 1) throw conflict();
                            return new PlanReference(plan);
                        });
        return get(entity, store, check, reference.planNo());
    }

    private PlanVO effective(String entity, String store, String check, Supplier<PlanVO> command) {
        if (orderSources == null || effectiveSources.get() != null) return command.get();
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CORE business reads must not hold FOOD locks");
        var before = readEffective(entity, store, check);
        effectiveSources.set(before);
        try {
            var result = command.get();
            var after = readEffective(entity, store, check);
            if (!effectiveDigest(before).equals(effectiveDigest(after))) {
                transaction(
                        () -> {
                            if (businessGuard != null)
                                businessGuard.invalidateShares(
                                        entity,
                                        store,
                                        check,
                                        "SYSTEM",
                                        "Original effective receivable changed");
                            return null;
                        });
                effectiveSources.set(after);
                return getLocal(entity, store, check, result.planNo());
            }
            return result;
        } finally {
            effectiveSources.remove();
        }
    }

    private Map<String, Result> readEffective(String entity, String store, String check) {
        var owner = orderSources.get();
        if (owner == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var result = new TreeMap<String, Result>();
        for (String batch : plans.currentBatchNos(entity, store, check)) {
            var binding =
                    bindings.selectOne(
                            Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                    .eq(FnbCoreOrderBinding::getTenantNo, "MAIN")
                                    .eq(FnbCoreOrderBinding::getEntityNo, entity)
                                    .eq(FnbCoreOrderBinding::getBatchNo, batch)
                                    .eq(FnbCoreOrderBinding::getStatus, "BOUND"));
            if (binding == null) throw conflict();
            result.put(
                    batch,
                    owner.getForStore(
                            new Scope("MAIN", entity, store),
                            "SYSTEM",
                            binding.getPaymentRootNo()));
        }
        return Map.copyOf(result);
    }

    private String effectiveDigest(Map<String, Result> sources) {
        var economic = new TreeMap<String, Object>();
        sources.forEach(
                (batch, result) ->
                        economic.put(
                                batch,
                                Arrays.asList(
                                        result.orderNo(),
                                        result.subOrderNo(),
                                        result.actualReceivableMinor(),
                                        result.actualAllocations(),
                                        result.amendmentSourceHash())));
        return FoodCommandFingerprint.hash(json.writeValueAsString(economic));
    }

    private SourceSnapshot source(String entity, String store, FnbCheck check) {
        try {
            return checkedSource(entity, store, check);
        } catch (JacksonException | ArithmeticException malformed) {
            throw conflict();
        }
    }

    private SourceSnapshot checkedSource(String entity, String store, FnbCheck check) {
        if (!"OPEN".equals(check.getStatus())) throw conflict();
        var numbers = plans.currentBatchNos(entity, store, check.getCheckNo());
        if (numbers == null
                || numbers.isEmpty()
                || numbers.size() > 100
                || new HashSet<>(numbers).size() != numbers.size()) {
            throw conflict();
        }
        numbers = numbers.stream().sorted().toList();
        var batchSources = new ArrayList<BatchSource>();
        var allocationSources = new ArrayList<AllocationSource>();
        var allocationIds = new HashSet<String>();
        String currency = null;
        long total = 0;
        long effectiveTotal = 0;
        boolean amended = false;
        for (var number : numbers) {
            var batch =
                    batches.selectOne(
                            Wrappers.<FnbOrderBatch>lambdaQuery()
                                    .eq(FnbOrderBatch::getTenantNo, "MAIN")
                                    .eq(FnbOrderBatch::getEntityNo, entity)
                                    .eq(FnbOrderBatch::getStoreNo, store)
                                    .eq(FnbOrderBatch::getBatchNo, number)
                                    .last("FOR UPDATE"));
            if (batch == null
                    || !"ACCEPTED".equals(batch.getStatus())
                    || batch.getAcceptedAt() == null
                    || batch.getFrozenRequest() == null
                    || batch.getCartVersion() == null
                    || batch.getVersion() == null
                    || batch.getVersion() < 0
                    || !entity.equals(batch.getEntityNo())
                    || !store.equals(batch.getStoreNo())
                    || !number.equals(batch.getBatchNo())) throw conflict();
            var memberships = plans.liveMemberships(entity, number);
            if (memberships == null
                    || memberships.size() > 1
                    || !memberships.isEmpty() && !number.equals(memberships.getFirst().batchNo()))
                throw conflict();
            String batchOwner =
                    memberships.isEmpty() ? batch.getCheckNo() : memberships.getFirst().checkNo();
            if (plans.blockingReturns(entity, store, number) > 0) throw conflict();
            var binding =
                    bindings.selectOne(
                            Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                    .eq(FnbCoreOrderBinding::getTenantNo, "MAIN")
                                    .eq(FnbCoreOrderBinding::getEntityNo, entity)
                                    .eq(FnbCoreOrderBinding::getBatchNo, number)
                                    .eq(FnbCoreOrderBinding::getStatus, "BOUND"));
            if (binding == null
                    || binding.getPaymentRootNo() == null
                    || binding.getStoreOrderNo() == null
                    || binding.getBindingNo() == null
                    || binding.getVersion() == null
                    || !entity.equals(binding.getEntityNo())
                    || !number.equals(binding.getBatchNo())) throw conflict();
            var authoritative =
                    effectiveSources.get() == null ? null : effectiveSources.get().get(number);
            if (orderSources != null && authoritative == null) throw conflict();
            var currentAllocations = new HashMap<Long, Allocation>();
            if (authoritative != null) {
                if (!binding.getPaymentRootNo().equals(authoritative.orderNo())
                        || !binding.getStoreOrderNo().equals(authoritative.subOrderNo())
                        || authoritative.sourceVersion() != batch.getCartVersion()
                        || authoritative.actualReceivableMinor() == null) throw conflict();
                for (var a : authoritative.actualAllocations())
                    if (currentAllocations.put(a.orderItemId(), a) != null) throw conflict();
                long currentTotal =
                        authoritative.actualAllocations().stream()
                                .mapToLong(Allocation::amountMinor)
                                .reduce(0, Math::addExact);
                if (currentTotal != authoritative.actualReceivableMinor()) throw conflict();
                amended |= authoritative.amendmentSourceHash() != null;
            }
            var frozen = json.readTree(batch.getFrozenRequest());
            if (frozen == null
                    || !frozen.has("schema")
                    || !Set.of(1, 2).contains(json.treeToValue(frozen.get("schema"), Integer.class))
                    || !frozen.has("quote")
                    || !frozen.has("facts")
                    || !frozen.get("facts").has("payload")
                    || !frozen.get("facts").get("payload").has("units")) throw conflict();
            var quote = json.treeToValue(frozen.get("quote"), QuoteVO.class);
            var units = frozen.get("facts").get("payload").get("units");
            if (!units.isArray()
                    || units.size() == 0
                    || units.size() > 1000
                    || quote == null
                    || quote.totals() == null
                    || quote.currency() == null
                    || !quote.currency().matches("[A-Z]{3}")
                    || quote.totals().receivableMinor() < 0
                    || quote.quoteNo() == null
                    || quote.confirmationDigest() == null
                    || !Objects.equals(batch.getQuoteRef(), quote.quoteNo())
                    || !Objects.equals(batch.getQuoteHash(), quote.confirmationDigest())
                    || quote.sourceVersion() != batch.getCartVersion()) throw conflict();
            if (currency == null) currency = quote.currency();
            else if (!currency.equals(quote.currency())) throw conflict();
            var expectedUnits = new HashMap<String, Unit>();
            for (var node : units) {
                var unit = json.treeToValue(node, Unit.class);
                if (unit == null
                        || unit.sourceLineNo() == null
                        || unit.componentRef() == null
                        || unit.quantity() == null
                        || unit.quantity().signum() <= 0
                        || expectedUnits.put(unit.sourceLineNo() + ":" + unit.componentRef(), unit)
                                != null) {
                    throw conflict();
                }
            }
            var orderLines =
                    lines.selectList(
                            Wrappers.<FnbOrderLine>lambdaQuery()
                                    .eq(FnbOrderLine::getTenantNo, "MAIN")
                                    .eq(FnbOrderLine::getEntityNo, entity)
                                    .eq(FnbOrderLine::getBatchNo, number)
                                    .orderByAsc(FnbOrderLine::getFoodLineNo));
            if (orderLines.isEmpty()) throw conflict();
            long batchTotal = 0;
            long selectedTotal = 0;
            long selectedEffective = 0;
            int count = 0;
            var usedUnits = new HashSet<String>();
            var originalItems = new HashSet<Long>();
            for (var line : orderLines) {
                if (line.getFoodLineNo() == null
                        || line.getSourceLineRef() == null
                        || line.getVersion() == null
                        || !number.equals(line.getBatchNo())
                        || !entity.equals(line.getEntityNo())) throw conflict();
                var lineOwners = plans.lineOwners(entity, line.getFoodLineNo());
                if (lineOwners.size() > 1) throw conflict();
                boolean selected =
                        check.getCheckNo()
                                .equals(lineOwners.isEmpty() ? batchOwner : lineOwners.getFirst());
                var rows =
                        allocations.selectList(
                                Wrappers.<FnbCoreLineAllocation>lambdaQuery()
                                        .eq(FnbCoreLineAllocation::getTenantNo, "MAIN")
                                        .eq(FnbCoreLineAllocation::getEntityNo, entity)
                                        .eq(
                                                FnbCoreLineAllocation::getFoodLineNo,
                                                line.getFoodLineNo())
                                        .orderByAsc(FnbCoreLineAllocation::getAllocationNo));
                if (rows.isEmpty()) throw conflict();
                for (var row : rows) {
                    String unitKey = line.getSourceLineRef() + ":" + row.getSourceComponentRef();
                    var unit = expectedUnits.get(unitKey);
                    if (row.getAllocationNo() == null
                            || !allocationIds.add(row.getAllocationNo())
                            || unit == null
                            || !usedUnits.add(unitKey)
                            || row.getOrderItemId() == null
                            || row.getOrderItemId() <= 0
                            || !originalItems.add(row.getOrderItemId())
                            || row.getCoreAmountMinor() == null
                            || row.getCoreAmountMinor() < 0
                            || !currency.equals(row.getCurrency())
                            || row.getQty() == null
                            || row.getQty().compareTo(unit.quantity()) != 0
                            || row.getCoreSnapshotHash() == null
                            || !row.getCoreSnapshotHash().matches("[0-9a-f]{64}")
                            || row.getUnit() == null
                            || row.getVersion() == null
                            || !entity.equals(row.getEntityNo())
                            || !line.getFoodLineNo().equals(row.getFoodLineNo())) throw conflict();
                    batchTotal = Math.addExact(batchTotal, row.getCoreAmountMinor());
                    count++;
                    var effective =
                            authoritative == null
                                    ? null
                                    : currentAllocations.get(row.getOrderItemId());
                    if (authoritative != null
                            && (effective == null
                                    || !line.getSourceLineRef().equals(effective.sourceLineNo())
                                    || !Objects.equals(
                                            row.getSourceComponentRef(), effective.componentRef())
                                    || !row.getCurrency().equals(effective.currency())
                                    || effective.saleQuantity().signum() < 0
                                    || effective.amountMinor() < 0)) throw conflict();
                    if (selected) {
                        selectedTotal = Math.addExact(selectedTotal, row.getCoreAmountMinor());
                        selectedEffective =
                                Math.addExact(
                                        selectedEffective,
                                        effective == null
                                                ? row.getCoreAmountMinor()
                                                : effective.amountMinor());
                        allocationSources.add(
                                new AllocationSource(
                                        row.getAllocationNo(),
                                        number,
                                        line.getFoodLineNo(),
                                        line.getSourceLineRef(),
                                        row.getSourceComponentRef(),
                                        row.getOrderItemId(),
                                        row.getQty(),
                                        row.getUnit(),
                                        row.getCoreSnapshotHash(),
                                        row.getVersion(),
                                        line.getVersion(),
                                        line.getSelectionVersion(),
                                        FoodCommandFingerprint.hash(
                                                "business-share-line:v1",
                                                json.writeValueAsString(line)),
                                        row.getCurrency(),
                                        row.getCoreAmountMinor(),
                                        authoritative == null
                                                        || authoritative.amendmentSourceHash()
                                                                == null
                                                ? null
                                                : effective.saleQuantity(),
                                        authoritative == null
                                                        || authoritative.amendmentSourceHash()
                                                                == null
                                                ? null
                                                : effective.amountMinor(),
                                        authoritative == null
                                                ? null
                                                : authoritative.amendmentSourceHash()));
                    }
                }
            }
            if (count != expectedUnits.size() || batchTotal != quote.totals().receivableMinor())
                throw conflict();
            total = Math.addExact(total, selectedTotal);
            effectiveTotal = Math.addExact(effectiveTotal, selectedEffective);
            batchSources.add(
                    new BatchSource(
                            number,
                            batch.getCheckNo(),
                            batch.getVersion(),
                            binding.getBindingNo(),
                            binding.getVersion(),
                            binding.getPaymentRootNo(),
                            binding.getStoreOrderNo(),
                            FoodCommandFingerprint.hash(
                                    "business-share-batch:v1", batch.getFrozenRequest()),
                            memberships,
                            authoritative == null ? null : authoritative.amendmentSourceHash()));
        }
        if (allocationSources.size() > 1000
                || !numbers.equals(
                        plans.currentBatchNos(entity, store, check.getCheckNo()).stream()
                                .sorted()
                                .toList())) throw conflict();
        return new SourceSnapshot(
                entity,
                store,
                check.getCheckNo(),
                check.getVersion(),
                currency,
                total,
                batchSources,
                allocationSources.stream()
                        .sorted(Comparator.comparing(AllocationSource::allocationNo))
                        .toList(),
                amended ? effectiveTotal : null);
    }

    private FnbCheck check(String entity, String store, String number) {
        var row =
                checks.selectOne(
                        Wrappers.<FnbCheck>lambdaQuery()
                                .eq(FnbCheck::getTenantNo, "MAIN")
                                .eq(FnbCheck::getEntityNo, entity)
                                .eq(FnbCheck::getStoreNo, store)
                                .eq(FnbCheck::getCheckNo, number)
                                .last("FOR UPDATE"));
        if (row == null) throw missing();
        return row;
    }

    private FnbBusinessSharePlan plan(String entity, String store, String check, String number) {
        var row =
                plans.selectOne(
                        Wrappers.<FnbBusinessSharePlan>lambdaQuery()
                                .eq(FnbBusinessSharePlan::getTenantNo, "MAIN")
                                .eq(FnbBusinessSharePlan::getEntityNo, entity)
                                .eq(FnbBusinessSharePlan::getStoreNo, store)
                                .eq(FnbBusinessSharePlan::getCheckNo, check)
                                .eq(FnbBusinessSharePlan::getPlanNo, number)
                                .last("FOR UPDATE"));
        if (row == null) throw missing();
        return row;
    }

    private PlanVO view(FnbBusinessSharePlan row, boolean current) {
        String status = !current && !CANCELLED.equals(row.getStatus()) ? "STALE" : row.getStatus();
        return new PlanVO(
                row.getPlanNo(),
                row.getCheckNo(),
                row.getVersion(),
                status,
                row.getStatus(),
                row.getMode(),
                row.getCurrency(),
                row.getOriginalAmountMinor(),
                row.getSourceHash(),
                row.getPlanHash(),
                current,
                false,
                true,
                json.readValue(row.getSourceSnapshot(), SourceSnapshot.class),
                json.readValue(row.getDivisionSnapshot(), Division.class),
                json.readValue(row.getDefinitionSnapshot(), CreateReq.class),
                row.getReason(),
                row.getCreatedBy(),
                row.getConfirmedBy(),
                row.getCancelledBy(),
                row.getEffectiveAmountMinor());
    }

    private static CreateReq canonical(CreateReq request) {
        return new CreateReq(
                request.checkVersion(),
                request.mode(),
                request.people().stream().sorted(Comparator.comparing(Person::personNo)).toList(),
                request.lineWeights() == null
                        ? List.of()
                        : request.lineWeights().stream()
                                .sorted(
                                        Comparator.comparing(LineWeight::foodLineNo)
                                                .thenComparing(LineWeight::personNo))
                                .toList(),
                request.reason());
    }

    private String sourceHash(SourceSnapshot source) {
        return FoodCommandFingerprint.hash(
                "business-share-source:v1", json.writeValueAsString(source));
    }

    private PlanReference command(
            String entity,
            String store,
            String actor,
            String check,
            String key,
            String action,
            Object request,
            Supplier<PlanReference> body) {
        return transaction(
                () ->
                        idem.execute(
                                key,
                                FoodCommandFingerprint.scope(
                                        "BUSINESS_SHARE:" + action, entity, store),
                                actor,
                                FoodCommandFingerprint.hash(
                                        "business-share-command:v1",
                                        check,
                                        json.writeValueAsString(request)),
                                PlanReference.class,
                                body));
    }

    private <T> T transaction(Supplier<T> body) {
        try {
            return DataScopeContext.executeWithoutScope(() -> write.execute(status -> body.get()));
        } catch (ConcurrencyFailureException
                | DuplicateKeyException
                | ArithmeticException invalid) {
            throw conflict();
        }
    }

    private static void stamp(FnbBusinessSharePlan row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(row.getCreatedAt());
    }

    private static void context(String entity, String store, String check) {
        id(entity);
        id(store);
        id(check);
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) throw bad();
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw bad();
    }

    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 255) throw bad();
    }

    private static void version(Long value) {
        if (value == null || value < 0 || value == Long.MAX_VALUE) throw bad();
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw bad();
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

    /** Stored idempotency receipt deliberately contains no mutable source-validity assertion. */
    public record PlanReference(String planNo) {}
}
