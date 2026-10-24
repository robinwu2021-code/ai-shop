package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.food.dto.CancelDiningOrderReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.CreateStaffDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.SalesQuotaClaimSource;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderModifier;
import ai.neargo.shop.food.mapper.FoodMappers.OrderModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.spi.trade.IndustryOrderCompositionFacts;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.DiningPricingSourcePolicy;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable FOOD intent -> original core order -> immutable source allocation. No financial state is invented. */
public class DiningOrderServiceImpl implements DiningOrderService {
    private final CheckCartService carts;
    private final KitchenTaskService kitchen;
    private final OutboxPublisher publisher;
    private final DiningPricingFactsService facts;
    private final StoreOperationService policies;
    private final SalesQuotaClaimService quotas;
    private final CheckCartMapper cartRows;
    private final StorePolicyMapper policyRows;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final OrderLineMapper lines;
    private final OrderComponentMapper components;
    private final OrderModifierMapper modifiers;
    private final ActionLogMapper actions;
    private final CoreLineAllocationMapper allocations;
    private final Supplier<IndustryOrderPort> orders;
    private final Supplier<PricingCheckoutPort> pricing;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;

    public DiningOrderServiceImpl(CheckCartService carts, DiningPricingFactsService facts,
            StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows,
            StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders,
            Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this(carts,facts,policies,quotas,cartRows,policyRows,batches,bindings,lines,allocations,
                orders,pricing,json,clock,manager,null);
    }

    public DiningOrderServiceImpl(CheckCartService carts, DiningPricingFactsService facts,
            StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows,
            StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders,
            Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager,
            KitchenTaskService kitchen) {
        this(carts,facts,policies,quotas,cartRows,policyRows,batches,bindings,lines,allocations,
                orders,pricing,json,clock,manager,kitchen,null);
    }

    public DiningOrderServiceImpl(CheckCartService carts,DiningPricingFactsService facts,
            StoreOperationService policies,SalesQuotaClaimService quotas,CheckCartMapper cartRows,
            StorePolicyMapper policyRows,OrderBatchMapper batches,CoreOrderBindingMapper bindings,
            OrderLineMapper lines,CoreLineAllocationMapper allocations,Supplier<IndustryOrderPort> orders,
            Supplier<PricingCheckoutPort> pricing,ObjectMapper json,Clock clock,PlatformTransactionManager manager,
            KitchenTaskService kitchen,OutboxPublisher publisher) {
        this(carts, facts, policies, quotas, cartRows, policyRows, batches, bindings, lines, allocations,
                orders, pricing, json, clock, manager, kitchen, publisher, null);
    }
    public DiningOrderServiceImpl(CheckCartService carts, DiningPricingFactsService facts,
            StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows,
            StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders,
            Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager,
            KitchenTaskService kitchen, OutboxPublisher publisher, OrderComponentMapper components) {
        this(carts, facts, policies, quotas, cartRows, policyRows, batches, bindings, lines, allocations,
                orders, pricing, json, clock, manager, kitchen, publisher, components, null);
    }
    public DiningOrderServiceImpl(CheckCartService carts, DiningPricingFactsService facts,
            StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows,
            StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders,
            Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager,
            KitchenTaskService kitchen, OutboxPublisher publisher, OrderComponentMapper components,
            OrderModifierMapper modifiers) {
        this(carts, facts, policies, quotas, cartRows, policyRows, batches, bindings, lines, allocations,
                orders, pricing, json, clock, manager, kitchen, publisher, components, modifiers, null);
    }
    public DiningOrderServiceImpl(CheckCartService carts, DiningPricingFactsService facts,
            StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows,
            StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, Supplier<IndustryOrderPort> orders,
            Supplier<PricingCheckoutPort> pricing, ObjectMapper json, Clock clock, PlatformTransactionManager manager,
            KitchenTaskService kitchen, OutboxPublisher publisher, OrderComponentMapper components,
            OrderModifierMapper modifiers, ActionLogMapper actions) {
        this.actions = actions;
        this.modifiers = modifiers;
        this.components = components;
        this.kitchen = kitchen; this.publisher=publisher;
        this.carts = carts; this.facts = facts; this.policies = policies; this.quotas = quotas;
        this.cartRows = cartRows; this.policyRows = policyRows; this.batches = batches; this.bindings = bindings;
        this.lines = lines; this.allocations = allocations; this.orders = orders; this.pricing = pricing;
        this.json = json; this.clock = clock; this.write = new TransactionTemplate(manager);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private record Frozen(int schema, DiningPriceFacts facts, QuoteVO quote, StoreOperationPolicyResultVO policy,
            FrozenRoutes kitchenRoutes) { }

    @Override public DiningOrderBatchVO staffPlace(String entity, String store, String actor,
            CreateStaffDiningOrderReq req, String key) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(actor);
        CartInputPolicy.replayKey(key);
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CartInputPolicy.key(req.buyerNo()); request(req.order());
        if (!"POSTPAY".equals(req.payMode()) || publisher == null || kitchen == null) throw unsupported();
        var authorizedResult = carts.getCart(entity, store, req.cartNo());
        if (authorizedResult == null || authorizedResult.cart() == null) throw missing();
        var authorized = authorizedResult.cart();
        if (!store.equals(authorized.storeNo())) throw missing();
        var cart = sql(() -> cart(store, req.cartNo(), false));
        if (!entity.equals(cart.getEntityNo())) throw missing();
        if (!List.of("POS", "WAITER").contains(cart.getOrderSource())
                || !("PICKUP".equals(cart.getFulfillment()) || "DINE_IN".equals(cart.getFulfillment()))
                || "DINE_IN".equals(cart.getFulfillment()) && cart.getCheckNo()==null
                || cart.getBuyerNo() != null && !req.buyerNo().equals(cart.getBuyerNo())) throw unsupported();
        String requestKey = PricingSnapshotHash.of(List.of("FOOD_STAFF_PLACEMENT_KEY_V1", actor, key));
        String hash = PricingSnapshotHash.of(List.of("FOOD_STAFF_PLACEMENT_REQUEST_V1", entity, store, actor, req));
        var existing = sql(() -> byKey(entity, store, requestKey));
        FnbOrderBatch batch;
        if (existing != null) {
            replay(existing, hash, req.buyerNo(), req.cartNo());
            if (!actor.equals(existing.getOperatorNo()) || !"MERCHANT".equals(existing.getActorRealm())) {
                throw conflict();
            }
            batch = existing;
        } else {
            if ("DINE_IN".equals(cart.getFulfillment()))
                carts.requireDiningRoomCart(entity,store,req.cartNo());
            var source = source(req.cartNo(), req.version());
            var evidence = facts.staff(entity, store, req.cartNo(), req.version());
            saleableUnits(evidence, req.payMode());
            var projection = DiningPricingSourcePolicy.project(evidence, new Scope("MAIN", entity, store),
                    source, null);
            IndustryOrderCompositionFacts.require(projection, IndustryOrderPort.Mode.valueOf(req.payMode()));
            var quote = pricing().revalidateStaff(new Scope("MAIN", entity, store), actor, source,
                    req.quoteNo(), req.confirmationDigest());
            if (!quote.sourceHash().equals(projection.prices().sourceSnapshotHash())) throw conflict();
            var policy = policies.getPolicy(entity, store, new GetPolicyQuery(clock.instant()));
            if (!req.payMode().equals(policy.payPolicy())) throw conflict();
            var routes = kitchen.freezeRoutes(entity, store,
                    evidence.payload().units().stream().map(u -> u.skuNo()).distinct().toList());
            batch = sql(() -> write.execute(tx -> prepare(cart, req.order(), requestKey, hash, actor,
                    req.buyerNo(), "MERCHANT", new Frozen(2, evidence, quote, policy, routes))));
        }
        if (List.of("CANCELLED", "FAILED").contains(batch.getStatus())) return view(batch, null);
        if ("CANCEL_PENDING".equals(batch.getStatus())) return recoverOriginal(entity, store, batch.getBatchNo());
        if (binding(batch) != null) return currentStaff(batch);
        var command = new Command(identity(batch, batch.getOperatorNo()),
                source(batch.getCartNo(), batch.getCartVersion()), null, batch.getQuoteRef(), batch.getQuoteHash(),
                IndustryOrderPort.Mode.POSTPAY, null);
        final Result result;
        try {
            result = "DINE_IN".equals(batch.getFulfillment())
                    ? orders().placeForDiningRoom(command,batch.getBatchNo())
                    : orders().placeForStore(command,batch.getBatchNo());
        }
        catch (RuntimeException failure) { unknown(batch); throw failure; }
        if (!committed(result) || !unpaidReady(result)) {
            unknown(batch); return view(reload(batch), result);
        }
        bind(batch, result);
        return view(reload(batch), result);
    }
    @Override public DiningOrderBatchVO staffCancel(String entity, String store, String actor, String number,
            CancelDiningOrderReq request, String key) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(actor);
        CartInputPolicy.key(number); CartInputPolicy.replayKey(key);
        if (actions == null) throw unsupported();
        if (request == null || request.version() == null || request.version() < 0 || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 255) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String requestNo = PricingSnapshotHash.of(List.of("FOOD_CANCEL_KEY_V1", entity, store, key));
        String hash = PricingSnapshotHash.of(List.of("FOOD_CANCEL_REQUEST_V1", entity, store, actor, number, request));
        var original = scopedBatch(entity, store, number);
        sql(() -> write.execute(tx -> {
            lockStore(entity, store);
            cart(store, original.getCartNo(), true);
            var batch = reload(original);
            var prior = cancellation(batch);
            if (prior != null) {
                if (!requestNo.equals(prior.getRequestNo()) || !hash.equals(prior.getBeforeRef())
                        || !actor.equals(prior.getOperatorNo())) throw conflict();
                return null;
            }
            if (!"MERCHANT".equals(batch.getActorRealm()) || !"POSTPAY".equals(batch.getPayMode())
                    || !List.of("POS", "WAITER").contains(batch.getSource())
                    || !List.of("PICKUP", "DINE_IN").contains(batch.getFulfillment())
                    || !List.of("PREPARED", "PLACING", "UNKNOWN").contains(batch.getStatus())
                    || binding(batch) != null || !request.version().equals(batch.getVersion())) throw conflict();
            var audit = new FnbActionLog(); stamp(audit, entity, actor);
            audit.setEntityNo(entity); audit.setActionNo(BizKey.next("FACT")); audit.setStoreNo(store);
            audit.setObjectType("ORDER_BATCH"); audit.setObjectNo(number); audit.setAction("CANCEL_PLACEMENT");
            audit.setOperatorNo(actor); audit.setRequestNo(requestNo); audit.setReason(request.reason());
            audit.setBeforeRef(hash); audit.setAfterRef(batch.getQuoteHash());
            if (actions.insert(audit) != 1) throw conflict();
            batch.setStatus("CANCEL_PENDING"); batch.setUpdatedBy(actor);
            if (batches.updateById(batch) != 1) throw conflict();
            return null;
        }));
        var current = reload(original);
        return "CANCELLED".equals(current.getStatus()) ? view(current, null) : recoverOriginal(entity, store, number);
    }
    @Override public Snapshot authorizeCancellation(Command command, String key, String fulfillment) {
        outside(); CartInputPolicy.key(key);
        if (command == null || command.identity() == null || command.source() == null) throw unsupported();
        var identity = command.identity();
        if (!"MAIN".equals(identity.tenantNo())) throw unsupported();
        var batch = scopedBatch(identity.entityNo(), identity.storeNo(), key);
        if (!"MERCHANT".equals(batch.getActorRealm()) || !"POSTPAY".equals(batch.getPayMode())
                || !"CANCEL_PENDING".equals(batch.getStatus()) || cancellation(batch) == null || binding(batch) != null
                || !List.of("POS", "WAITER").contains(batch.getSource())
                || !List.of("PICKUP", "DINE_IN").contains(batch.getFulfillment())) throw unsupported();
        var expected = new Command(identity(batch, batch.getOperatorNo()),
                source(batch.getCartNo(), batch.getCartVersion()), null, batch.getQuoteRef(), batch.getQuoteHash(),
                IndustryOrderPort.Mode.POSTPAY, null);
        String originalFulfillment = "DINE_IN".equals(batch.getFulfillment())
                ? Fulfillments.DINE_IN : Fulfillments.STORE_PICKUP;
        if (!expected.equals(command) || !originalFulfillment.equals(fulfillment)) throw conflict();
        var frozen = json.readValue(batch.getFrozenRequest(), Frozen.class);
        if (frozen == null || frozen.schema() != 2 || frozen.facts() == null || frozen.facts().payload() == null
                || frozen.quote() == null || frozen.facts().payload().cart() == null) throw unsupported();
        var cart = frozen.facts().payload().cart();
        if (!batch.getCartNo().equals(cart.cartNo()) || !batch.getStoreNo().equals(cart.storeNo())
                || !batch.getEntityNo().equals(frozen.facts().payload().entityNo())
                || !batch.getSource().equals(cart.orderSource()) || !batch.getFulfillment().equals(cart.fulfillment())
                || !Objects.equals(batch.getCartVersion(), cart.version())
                || !batch.getQuoteRef().equals(frozen.quote().quoteNo())
                || !batch.getQuoteHash().equals(frozen.quote().confirmationDigest())
                || batch.getCartVersion() != frozen.quote().sourceVersion()) throw conflict();
        var projection = DiningPricingSourcePolicy.project(frozen.facts(),
                new Scope("MAIN", batch.getEntityNo(), batch.getStoreNo()), command.source(), null);
        if (!frozen.quote().sourceHash().equals(projection.prices().sourceSnapshotHash())) throw conflict();
        return projection;
    }
    private FnbOrderBatch scopedBatch(String entity, String store, String number) {
        var row = sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getTenantNo, "MAIN").eq(FnbOrderBatch::getEntityNo, entity)
                .eq(FnbOrderBatch::getStoreNo, store).eq(FnbOrderBatch::getBatchNo, number)));
        if (row == null) throw missing();
        return row;
    }
    private FnbActionLog cancellation(FnbOrderBatch batch) {
        if (actions == null) return null;
        return sql(() -> actions.selectOne(Wrappers.<FnbActionLog>lambdaQuery()
                .eq(FnbActionLog::getTenantNo, "MAIN").eq(FnbActionLog::getEntityNo, batch.getEntityNo())
                .eq(FnbActionLog::getStoreNo, batch.getStoreNo()).eq(FnbActionLog::getObjectType, "ORDER_BATCH")
                .eq(FnbActionLog::getObjectNo, batch.getBatchNo()).eq(FnbActionLog::getAction, "CANCEL_PLACEMENT")));
    }
    private Result cancelPending(FnbOrderBatch original) {
        var audit = cancellation(original);
        if (audit == null || !"MERCHANT".equals(original.getActorRealm())
                || !"POSTPAY".equals(original.getPayMode())) throw unsupported();
        var command = new Command(identity(original, original.getOperatorNo()),
                source(original.getCartNo(), original.getCartVersion()), null, original.getQuoteRef(),
                original.getQuoteHash(), IndustryOrderPort.Mode.POSTPAY, null);
        try {
            return orders().cancelForStore(command, original.getBatchNo(),
                    "DINE_IN".equals(original.getFulfillment()) ? Fulfillments.DINE_IN : Fulfillments.STORE_PICKUP);
        } catch (BizException failure) {
            if (failure.errorCode() != ErrorCode.CONFLICT) throw failure;
            // A committed original won the race. Never cancel an accepted service debt or invent a release.
            var current = orders().queryForStore(command.identity(), original.getBatchNo());
            if (!committed(current) || !unpaidReady(current)) throw failure;
            sql(() -> write.execute(tx -> {
                lockStore(original.getEntityNo(), original.getStoreNo());
                cart(original.getStoreNo(), original.getCartNo(), true);
                var locked = reload(original);
                if (!"CANCEL_PENDING".equals(locked.getStatus())) return null;
                var rejected = new FnbActionLog(); stamp(rejected, locked.getEntityNo(), audit.getOperatorNo());
                rejected.setEntityNo(locked.getEntityNo()); rejected.setActionNo(BizKey.next("FACT"));
                rejected.setStoreNo(locked.getStoreNo()); rejected.setObjectType("ORDER_BATCH");
                rejected.setObjectNo(locked.getBatchNo()); rejected.setAction("REJECT_CANCEL_PLACEMENT");
                rejected.setOperatorNo(audit.getOperatorNo()); rejected.setRequestNo(audit.getRequestNo());
                rejected.setReason("Original order already committed; use business returns");
                rejected.setBeforeRef(audit.getBeforeRef()); rejected.setAfterRef(current.orderNo());
                if (actions.insert(rejected) != 1) throw conflict();
                locked.setStatus("UNKNOWN"); locked.setUpdatedBy(audit.getOperatorNo());
                if (batches.updateById(locked) != 1) throw conflict();
                return null;
            }));
            return current;
        }
    }
    private DiningOrderBatchVO currentStaff(FnbOrderBatch original) {
        var core = orders().queryForStore(identity(original, original.getOperatorNo()), original.getBatchNo());
        if (committed(core) && unpaidReady(core)) bind(original, core);
        return view(reload(original), core);
    }
    private void saleableUnits(DiningPriceFacts evidence, String mode) {
        if (evidence == null || evidence.payload() == null || evidence.payload().units() == null
                || evidence.payload().units().isEmpty() || evidence.payload().units().stream().anyMatch(u ->
                        (!"MAIN".equals(u.kind()) && (!"ADDON".equals(u.kind()) || !"POSTPAY".equals(mode)
                                || components == null)) || u.componentRef() == null
                        || !u.componentRef().matches("[0-9a-f]{64}") || u.quantity() == null
                        || u.quantity().stripTrailingZeros().scale() > 0)) throw unsupported();
    }
    @Override public DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq req,
            String key) {
        outside(); request(req); CartInputPolicy.key(store); CartInputPolicy.replayKey(key); buyer(access);
        var authorized = carts.customerGetCart(req.cartNo(), access).cart();
        if (!store.equals(authorized.storeNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var cart = sql(() -> cart(store, req.cartNo(), false));
        if (!access.buyerNo().equals(cart.getBuyerNo()) || !"H5".equals(cart.getOrderSource())
                || !"PICKUP".equals(cart.getFulfillment())) throw unsupported();
        if("POSTPAY".equals(req.payMode()) && (publisher==null || kitchen==null)) throw unsupported();
        String requestKey = PricingSnapshotHash.of(List.of("FOOD_PLACEMENT_KEY_V1", access.buyerNo(), key));
        String hash = PricingSnapshotHash.of(List.of("FOOD_PLACEMENT_REQUEST_V1", store, access.buyerNo(), req));
        var existing = sql(() -> byKey(cart.getEntityNo(), store, requestKey));
        FnbOrderBatch batch;
        if (existing != null) {
            replay(existing, hash, access.buyerNo(), req.cartNo()); batch = existing;
        } else {
            var source = source(req.cartNo(), req.version());
            var sourceFacts = facts.customer(req.cartNo(), access, req.version());
            saleableUnits(sourceFacts, req.payMode());
            var quote = pricing().revalidateCustomer(source, credentials(access), req.quoteNo(),
                    req.confirmationDigest());
            var projection = DiningPricingSourcePolicy.project(sourceFacts, null, source,
                    sourceFacts.payload().cart().sessionNo() == null ? access.buyerNo()
                            : sourceFacts.payload().cart().sessionNo());
            IndustryOrderCompositionFacts.require(projection, IndustryOrderPort.Mode.valueOf(req.payMode()));
            if (!quote.sourceHash().equals(projection.prices().sourceSnapshotHash())) throw conflict();
            var policy = policies.getPolicy(cart.getEntityNo(), store, new GetPolicyQuery(clock.instant()));
            if (!req.payMode().equals(policy.payPolicy())) throw conflict();
            var routes = kitchen == null ? null : kitchen.freezeRoutes(cart.getEntityNo(),store,
                    sourceFacts.payload().units().stream().map(u -> u.skuNo()).distinct().toList());
            batch = sql(() -> write.execute(tx -> prepare(cart, req, requestKey, hash, access.buyerNo(),
                    new Frozen(2, sourceFacts, quote, policy, routes))));
        }
        if (List.of("CANCELLED", "FAILED").contains(batch.getStatus())) return view(batch, null);
        if (binding(batch) != null) return current(batch, access.buyerNo());
        var command = new Command(identity(batch, access.buyerNo()), source(batch.getCartNo(), batch.getCartVersion()),
                credentials(access), batch.getQuoteRef(), batch.getQuoteHash(), IndustryOrderPort.Mode.valueOf(batch.getPayMode()), "H5");
        final Result result;
        try { result = orders().place(command, batch.getBatchNo()); }
        catch (RuntimeException failure) {
            // A transport error does not prove the core intent absent. Keep the source and quota frozen for recovery.
            unknown(batch); throw failure;
        }
        if (!committed(result) || "POSTPAY".equals(batch.getPayMode()) && !unpaidReady(result)) {
            unknown(batch); return view(reload(batch), result);
        }
        bind(batch, result);
        return view(reload(batch), result);
    }
    @Override public DiningOrderBatchVO customerGet(CartAccess access, String number) {
        outside(); buyer(access); CartInputPolicy.key(number);
        var batch = sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getTenantNo, "MAIN").eq(FnbOrderBatch::getBatchNo, number)
                .eq(FnbOrderBatch::getBuyerNo, access.buyerNo())));
        if (batch == null) throw missing();
        carts.customerGetCart(batch.getCartNo(), access);
        return current(batch, access.buyerNo());
    }
    @Override public DiningOrderBatchVO staffGet(String entity, String store, String actor, String number) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(actor);
        CartInputPolicy.key(number);
        var batch = sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getTenantNo, "MAIN").eq(FnbOrderBatch::getEntityNo, entity)
                .eq(FnbOrderBatch::getStoreNo, store).eq(FnbOrderBatch::getBatchNo, number)));
        if (batch == null) throw missing();
        // Original money remains undisclosed until the source-bound core read verifies this staff scope.
        var binding = binding(batch);
        var result = binding == null ? null : orders().getForStore(
                new Scope("MAIN", entity, store), actor,
                binding.getPaymentRootNo());
        return view(batch, result);
    }
    @Override public DiningOrderBatchVO recoverOriginal(String entity, String store, String number) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(number);
        FnbOrderBatch original = sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getTenantNo, "MAIN").eq(FnbOrderBatch::getEntityNo, entity)
                .eq(FnbOrderBatch::getStoreNo, store).eq(FnbOrderBatch::getBatchNo, number)));
        if (original == null || original.getBuyerNo() == null || original.getFrozenRequest() == null) throw missing();
        CartInputPolicy.key(original.getBuyerNo());
        // The original key and persisted buyer/source are authority; recovery never asks for a fresh quote or place.
        Result result = "CANCEL_PENDING".equals(original.getStatus()) && "MERCHANT".equals(original.getActorRealm())
                ? cancelPending(original) : "MERCHANT".equals(original.getActorRealm())
                ? orders().queryForStore(identity(original, original.getOperatorNo()), original.getBatchNo())
                : orders().query(identity(original, original.getBuyerNo()), original.getBatchNo());
        if (result == null) return view(original, null);
        if ("RELEASED".equals(result.status())) {
            if ("MERCHANT".equals(original.getActorRealm()) && (!"POSTPAY".equals(result.orderMode())
                    || result.inventoryLifecycleVersion() != 3)) throw conflict();
            if (!Objects.equals(original.getQuoteRef(), result.quoteNo())
                    || original.getCartVersion() == null || original.getCartVersion() != result.sourceVersion()
                    || result.payAmountMinor() != null || !result.allocations().isEmpty()
                    || result.orderNo() == null || result.subOrderNo() == null) throw conflict();
            FnbOrderBatch cancelled = sql(() -> write.execute(tx -> {
                lockStore(entity, store);
                cart(store, original.getCartNo(), true);
                FnbOrderBatch locked = reload(original);
                if (binding(locked) != null) throw conflict();
                if ("CANCELLED".equals(locked.getStatus())) return locked;
                if (!List.of("PREPARED", "PLACING", "UNKNOWN", "CANCEL_PENDING").contains(locked.getStatus())) {
                    throw conflict();
                }
                quotas.release(entity, store, number);
                locked.setStatus("CANCELLED"); locked.setUpdatedBy("SYSTEM");
                if (batches.updateById(locked) != 1) throw conflict();
                return locked;
            }));
            return view(cancelled, result);
        }
        if (committed(result) && (!"POSTPAY".equals(original.getPayMode()) || unpaidReady(result))) {
            bind(original,result);
        }
        return view(reload(original), result);
    }

    private FnbOrderBatch prepare(FnbCheckCart original, CreateDiningOrderReq req, String key, String hash,
            String actor, Frozen frozen) {
        return prepare(original, req, key, hash, actor, actor, "CONSUMER", frozen);
    }
    private FnbOrderBatch prepare(FnbCheckCart original, CreateDiningOrderReq req, String key, String hash,
            String actor, String buyer, String realm, Frozen frozen) {
        var currentPolicy = lockStore(original.getEntityNo(), original.getStoreNo());
        if (!frozen.policy().policyNo().equals(currentPolicy.getPolicyNo())
                || currentPolicy.getVersion() == null || frozen.policy().version() != currentPolicy.getVersion()) {
            throw conflict();
        }
        if ("MERCHANT".equals(realm) && "DINE_IN".equals(original.getFulfillment()))
            carts.requireDiningRoomCart(original.getEntityNo(),original.getStoreNo(),original.getCartNo());
        var cart = cart(original.getStoreNo(), original.getCartNo(), true);
        var replay = byKey(original.getEntityNo(), original.getStoreNo(), key);
        if (replay != null) {
            replay(replay, hash, buyer, req.cartNo());
            if (!actor.equals(replay.getOperatorNo()) || !realm.equals(replay.getActorRealm())) throw conflict();
            return replay;
        }
        if (!"OPEN".equals(cart.getStatus()) || !Objects.equals(cart.getVersion(), req.version())
                || cartRows.activePlacementCount(cart.getEntityNo(), cart.getCartNo()) != 0) throw conflict();
        var batch = new FnbOrderBatch(); stamp(batch, cart.getEntityNo(), actor);
        batch.setEntityNo(cart.getEntityNo());
        batch.setBatchNo(BizKey.next("FBAT")); batch.setStoreNo(cart.getStoreNo());
        batch.setCheckNo(cart.getCheckNo()); batch.setRequestKey(key); batch.setRequestHash(hash);
        batch.setSource(cart.getOrderSource()); batch.setBuyerNo(buyer); batch.setOperatorNo(actor);
        batch.setStatus("PREPARED"); batch.setFrozenRequest(json.writeValueAsString(frozen));
        batch.setCartNo(cart.getCartNo()); batch.setCartVersion(req.version()); batch.setQuoteRef(req.quoteNo());
        batch.setQuoteHash(req.confirmationDigest()); batch.setBusinessDate(
                ServiceCalendarPolicy.businessDate(clock.instant(), frozen.policy()));
        batch.setPolicyNo(frozen.policy().policyNo()); batch.setFulfillment(cart.getFulfillment());
        batch.setPayMode(req.payMode()); batch.setActorRealm(realm);
        var claims = quotas.reserve(cart.getEntityNo(), cart.getStoreNo(), batch.getBatchNo(),
                cart.getOrderSource(), cart.getFulfillment(), frozen.facts().payload().units().stream()
                        .filter(u -> !"BUNDLE_INCLUDED".equals(u.kind()))
                        .map(u -> new SalesQuotaClaimSource(PricingSnapshotHash.of(List.of(u.sourceLineNo(),
                                Objects.requireNonNullElse(u.componentRef(), "ROOT"))), u.skuNo(), u.quantity()))
                        .toList());
        batch.setQuotaClaimRefs(json.writeValueAsString(claims)); batches.insert(batch); return batch;
    }
    private void bind(FnbOrderBatch original, Result result) {
        sql(() -> write.execute(tx -> {
            lockStore(original.getEntityNo(), original.getStoreNo());
            if ("MERCHANT".equals(original.getActorRealm()) && "DINE_IN".equals(original.getFulfillment())
                    && binding(reload(original)) == null) {
                // The source owner's check lock survives through real binding, acceptance and original cart closure.
                carts.requireDiningRoomCart(original.getEntityNo(),original.getStoreNo(),original.getCartNo());
            }
            var cart = cart(original.getStoreNo(), original.getCartNo(), true);
            var batch = reload(original);
            if (binding(batch) != null) {
                if("POSTPAY".equals(batch.getPayMode())) acceptUnpaid(batch,result);
                return null;
            }
            if (!List.of("PREPARED", "PLACING", "UNKNOWN").contains(batch.getStatus())) throw conflict();
            CartInputPolicy.key(result.orderNo()); CartInputPolicy.key(result.subOrderNo());
            if("POSTPAY".equals(batch.getPayMode()) && !unpaidReady(result)
                    || "PREPAY".equals(batch.getPayMode()) && result.inventoryLifecycleVersion()!=2) throw conflict();
            var frozen = json.readValue(batch.getFrozenRequest(), Frozen.class);
            if ("DINE_IN".equals(batch.getFulfillment())) validateDiningRoomFrozen(batch,cart,frozen);
            if (!result.quoteNo().equals(batch.getQuoteRef()) || result.sourceVersion() != batch.getCartVersion()
                    || result.payAmountMinor() != frozen.quote().totals().receivableMinor()
                    || !result.currency().equals(frozen.quote().currency())) throw conflict();
            var mapped = new HashMap<String, IndustryOrderPort.Allocation>();
            long sum = 0;
            for (var allocation : result.allocations()) {
                if (allocation.componentRef() == null || !allocation.componentRef().matches("[0-9a-f]{64}")
                        || mapped.put(allocation.sourceLineNo() + ":" + allocation.componentRef(), allocation) != null
                        || !result.currency().equals(allocation.currency())) throw unsupported();
                sum = Math.addExact(sum, allocation.amountMinor());
            }
            if (sum != result.payAmountMinor() || mapped.size() != frozen.facts().payload().units().size()) {
                throw conflict();
            }
            for (var input : frozen.facts().payload().cart().lines()) {
                var originalUnit = frozen.facts().payload().units().stream()
                        .filter(u -> "MAIN".equals(u.kind()) && u.sourceLineNo().equals(input.sourceLineRef()))
                        .findFirst().orElseThrow(DiningOrderServiceImpl::conflict);
                var allocation = mapped.get(input.sourceLineRef() + ":" + originalUnit.componentRef());
                if (allocation == null || !originalUnit.componentRef().equals(allocation.componentRef())
                        || input.qty().compareTo(BigDecimal.valueOf(allocation.qty())) != 0) {
                    throw conflict();
                }
                var line = new FnbOrderLine(); stamp(line, batch.getEntityNo(), batch.getOperatorNo());
                line.setEntityNo(batch.getEntityNo());
                line.setFoodLineNo(BizKey.next("FLIN")); line.setBatchNo(batch.getBatchNo());
                line.setGoodsNo(input.goodsNo()); line.setSkuNo(input.skuNo()); line.setQty(input.qty());
                line.setSeatNo(input.seatNo()); line.setCourseNo(input.courseNo());
                line.setSelectionVersion(frozen.facts().payload().menus().stream()
                        .filter(m -> m.menuNo().equals(input.menuNo())).findFirst().orElseThrow(
                                DiningOrderServiceImpl::conflict).revision());
                line.setDisplaySnapshot(json.writeValueAsString(input)); line.setSourceLineRef(input.sourceLineRef());
                line.setConfigRevisionNos(json.writeValueAsString(frozen.facts().payload().menus()));
                line.setUnit("EA"); lines.insert(line);
                var units = frozen.facts().payload().units().stream()
                        .filter(unit -> input.sourceLineRef().equals(unit.sourceLineNo())).toList();
                var componentNumbers = new HashMap<String, String>();
                for (var unit : units) {
                    var part = mapped.get(unit.sourceLineNo() + ":" + unit.componentRef());
                    if (part == null || unit.quantity().compareTo(BigDecimal.valueOf(part.qty())) != 0) {
                        throw conflict();
                    }
                    if (units.size() > 1) {
                        if (components == null) throw unsupported();
                        var component = new FnbOrderComponent(); stamp(component, batch.getEntityNo(), batch.getOperatorNo());
                        component.setEntityNo(batch.getEntityNo()); component.setComponentNo(BizKey.next("FCMP"));
                        component.setFoodLineNo(line.getFoodLineNo()); component.setSkuNo(unit.skuNo());
                        component.setComponentKind(unit.kind()); component.setSourceComponentRef(unit.componentRef());
                        componentNumbers.put(unit.componentRef(), component.getComponentNo());
                        component.setQty(unit.quantity()); component.setSelectionSnapshot(json.writeValueAsString(unit));
                        components.insert(component);
                    }
                    var coreLine = new FnbCoreLineAllocation();
                    stamp(coreLine, batch.getEntityNo(), batch.getOperatorNo());
                    coreLine.setEntityNo(batch.getEntityNo()); coreLine.setAllocationNo(BizKey.next("FCLA"));
                    coreLine.setFoodLineNo(line.getFoodLineNo()); coreLine.setSourceComponentRef(part.componentRef());
                    coreLine.setOrderItemId(part.orderItemId()); coreLine.setQty(unit.quantity());
                    coreLine.setCoreSnapshotHash(part.hash()); coreLine.setCoreAmountMinor(part.amountMinor());
                    coreLine.setCurrency(part.currency()); coreLine.setUnit("EA"); allocations.insert(coreLine);
                }
                if (modifiers != null) for (var selected : input.selections()) {
                    var modifier = new FnbOrderModifier(); stamp(modifier, batch.getEntityNo(), batch.getOperatorNo());
                    modifier.setEntityNo(batch.getEntityNo()); modifier.setSelectionNo(BizKey.next("FSEL"));
                    modifier.setFoodLineNo(line.getFoodLineNo()); modifier.setSourceKind(selected.sourceKind());
                    modifier.setSourceNo(selected.sourceNo()); modifier.setQty(selected.qty());
                    modifier.setSelectionSnapshot(json.writeValueAsString(selected));
                    if ("MODIFIER".equals(selected.sourceKind())) {
                        String ref = PricingSnapshotHash.of(List.of("OPTION", selected.groupNo(), selected.sourceNo()));
                        modifier.setComponentNo(componentNumbers.get(ref));
                        if (modifier.getComponentNo() == null) throw conflict();
                    }
                    modifiers.insert(modifier);
                }
            }
            var binding = new FnbCoreOrderBinding(); stamp(binding, batch.getEntityNo(), batch.getOperatorNo());
            binding.setEntityNo(batch.getEntityNo());
            binding.setBindingNo(BizKey.next("FCOB")); binding.setBatchNo(batch.getBatchNo());
            binding.setPaymentRootNo(result.orderNo()); binding.setStoreOrderNo(result.subOrderNo());
            binding.setStatus("BOUND"); bindings.insert(binding);
            batch.setStatus("BOUND"); batch.setUpdatedBy(batch.getOperatorNo());
            if(batches.updateById(batch)!=1) throw conflict();
            if("POSTPAY".equals(batch.getPayMode())) acceptUnpaid(batch,result);
            if ("DINE_IN".equals(batch.getFulfillment()) && !"ACCEPTED".equals(batch.getStatus())) throw conflict();
            if (!Objects.equals(cart.getVersion(), batch.getCartVersion()) || !"OPEN".equals(cart.getStatus())) {
                throw conflict();
            }
            int closed = cartRows.update(null, Wrappers.<FnbCheckCart>lambdaUpdate()
                    .eq(FnbCheckCart::getEntityNo, cart.getEntityNo()).eq(FnbCheckCart::getCartNo, cart.getCartNo())
                    .eq(FnbCheckCart::getVersion, cart.getVersion()).eq(FnbCheckCart::getStatus, "OPEN")
                    .set(FnbCheckCart::getStatus, "CLOSED")
                    .set(FnbCheckCart::getVersion, Math.addExact(cart.getVersion(), 1))
                    .set(FnbCheckCart::getUpdatedBy, batch.getOperatorNo())
                    .set(FnbCheckCart::getUpdatedAt, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
            if (closed != 1) throw conflict(); return null;
        }));
    }
    private void validateDiningRoomFrozen(FnbOrderBatch batch,FnbCheckCart cart,Frozen frozen) {
        if (!"MERCHANT".equals(batch.getActorRealm()) || !"POSTPAY".equals(batch.getPayMode())
                || batch.getCheckNo()==null || frozen==null || frozen.schema()!=2 || frozen.facts()==null || frozen.quote()==null
                || frozen.facts().payload()==null || frozen.facts().payload().cart()==null) throw conflict();
        var sourceCart=frozen.facts().payload().cart();
        if (!Objects.equals(batch.getCheckNo(),cart.getCheckNo())
                || !Objects.equals(batch.getCheckNo(),sourceCart.checkNo())
                || !batch.getCartNo().equals(sourceCart.cartNo()) || !batch.getStoreNo().equals(sourceCart.storeNo())
                || !"DINE_IN".equals(cart.getFulfillment()) || !"DINE_IN".equals(sourceCart.fulfillment())
                || !Objects.equals(batch.getSource(),cart.getOrderSource())
                || !Objects.equals(batch.getSource(),sourceCart.orderSource())
                || !Objects.equals(batch.getCartVersion(),sourceCart.version())
                || !Objects.equals(batch.getCartVersion(),cart.getVersion())
                || !batch.getEntityNo().equals(frozen.facts().payload().entityNo())
                || !batch.getQuoteRef().equals(frozen.quote().quoteNo())
                || !batch.getQuoteHash().equals(frozen.quote().confirmationDigest())
                || frozen.quote().sourceVersion()!=batch.getCartVersion()
                || !frozen.facts().sourceHash().equals(PricingSnapshotHash.of(frozen.facts().payload()))) throw conflict();
        var projection=DiningPricingSourcePolicy.project(frozen.facts(),
                new Scope("MAIN",batch.getEntityNo(),batch.getStoreNo()),
                source(batch.getCartNo(),batch.getCartVersion()),null);
        if (!frozen.quote().sourceHash().equals(projection.prices().sourceSnapshotHash())) throw conflict();
    }
    /** Joins the same local transaction as immutable source allocations, quota consumption and dispatch outbox. */
    private void acceptUnpaid(FnbOrderBatch batch,Result result) {
        if("ACCEPTED".equals(batch.getStatus())) return;
        if(publisher==null || !"BOUND".equals(batch.getStatus()) || !unpaidReady(result)) throw conflict();
        var bound=binding(batch);
        if(bound==null || !bound.getPaymentRootNo().equals(result.orderNo())
                || !bound.getStoreOrderNo().equals(result.subOrderNo()) || !batch.getQuoteRef().equals(result.quoteNo())
                || batch.getCartVersion()!=result.sourceVersion()) throw conflict();
        quotas.consume(batch.getEntityNo(),batch.getStoreNo(),batch.getBatchNo());
        batch.setStatus("ACCEPTED"); batch.setAcceptedAt(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        batch.setUpdatedBy(batch.getOperatorNo());
        if(batches.updateById(batch)!=1) throw conflict();
        publisher.publish(new DiningOrderAccepted(batch.getBatchNo(),batch.getEntityNo(),batch.getStoreNo(),
                batch.getCheckNo(),result.orderNo(),result.subOrderNo(),batch.getBusinessDate(),batch.getFulfillment(),
                batch.getAcceptedAt().toInstant(ZoneOffset.UTC)),new PublishOptions("FOOD:BATCH:"+batch.getBatchNo(),1));
    }
    private static boolean unpaidReady(Result result) {
        return result!=null && "POSTPAY".equals(result.orderMode()) && result.inventoryLifecycleVersion()==3
                && result.acceptedUnpaid() && "WAIT_PAY".equals(result.status()) && result.fulfillmentStatus()!=null
                && List.of("WAIT_FULFILL","FULFILLING","COMPLETED").contains(result.fulfillmentStatus());
    }
    private DiningOrderBatchVO current(FnbOrderBatch batch, String actor) {
        var bound = binding(batch);
        Result core = bound == null ? orders().query(identity(batch, actor), batch.getBatchNo())
                : orders().get(identity(batch, actor), bound.getPaymentRootNo());
        if (committed(core) && (!"POSTPAY".equals(batch.getPayMode()) || unpaidReady(core))
                && (bound==null || "POSTPAY".equals(batch.getPayMode()) && !"ACCEPTED".equals(batch.getStatus()))) {
            bind(batch,core); batch=reload(batch);
        }
        return view(batch, core);
    }
    private DiningOrderBatchVO view(FnbOrderBatch batch, Result core) {
        var bound = binding(batch);
        return new DiningOrderBatchVO(batch.getBatchNo(), batch.getStoreNo(), batch.getCheckNo(), batch.getCartNo(),
                batch.getCartVersion(), batch.getQuoteRef(), batch.getStatus(), batch.getPayMode(),
                batch.getBusinessDate(), bound == null ? core == null ? null : core.orderNo() : bound.getPaymentRootNo(),
                bound == null ? core == null ? null : core.subOrderNo() : bound.getStoreOrderNo(),
                core == null ? null : core.status(), core == null ? null : core.currency(),
                core == null ? null : core.payAmountMinor(), batch.getVersion());
    }
    private void unknown(FnbOrderBatch batch) {
        sql(() -> write.execute(tx -> {
            batches.update(null, Wrappers.<FnbOrderBatch>lambdaUpdate().eq(FnbOrderBatch::getEntityNo, batch.getEntityNo())
                    .eq(FnbOrderBatch::getBatchNo, batch.getBatchNo())
                    .in(FnbOrderBatch::getStatus, "PREPARED", "PLACING", "UNKNOWN")
                    .set(FnbOrderBatch::getStatus, "UNKNOWN")); return null;
        }));
    }
    private FnbOrderBatch byKey(String entity, String store, String key) {
        return batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getTenantNo, "MAIN")
                .eq(FnbOrderBatch::getEntityNo, entity).eq(FnbOrderBatch::getStoreNo, store)
                .eq(FnbOrderBatch::getRequestKey, key));
    }
    private FnbOrderBatch reload(FnbOrderBatch batch) {
        return sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getEntityNo, batch.getEntityNo()).eq(FnbOrderBatch::getBatchNo, batch.getBatchNo())));
    }
    private FnbCoreOrderBinding binding(FnbOrderBatch batch) {
        return sql(() -> bindings.selectOne(Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                .eq(FnbCoreOrderBinding::getEntityNo, batch.getEntityNo())
                .eq(FnbCoreOrderBinding::getBatchNo, batch.getBatchNo()).eq(FnbCoreOrderBinding::getStatus, "BOUND")));
    }
    private FnbCheckCart cart(String store, String number, boolean lock) {
        var query = Wrappers.<FnbCheckCart>lambdaQuery().eq(FnbCheckCart::getTenantNo, "MAIN")
                .eq(FnbCheckCart::getStoreNo, store).eq(FnbCheckCart::getCartNo, number);
        if (lock) query.last("FOR UPDATE"); var row = cartRows.selectOne(query);
        if (row == null) throw missing(); return row;
    }
    private FnbStorePolicy lockStore(String entity, String store) {
        var rows = policyRows.selectList(Wrappers.<FnbStorePolicy>lambdaQuery()
                .eq(FnbStorePolicy::getEntityNo, entity).eq(FnbStorePolicy::getStoreNo, store)
                .orderByAsc(FnbStorePolicy::getPolicyNo).last("FOR UPDATE"));
        return rows.stream().filter(row -> row.getEffectiveFrom() != null
                && !row.getEffectiveFrom().isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
                .max(Comparator.comparing(FnbStorePolicy::getEffectiveFrom)).orElseThrow(
                        DiningOrderServiceImpl::conflict);
    }
    private void stamp(BaseEntity row, String entity, String actor) {
        row.setTenantNo("MAIN"); row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(actor); row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        row.setUpdatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
    private static Identity identity(FnbOrderBatch batch, String actor) {
        return new Identity("MAIN", batch.getEntityNo(), batch.getStoreNo(), batch.getBuyerNo(), actor);
    }
    private static PricingSourceRef source(String cart, long version) {
        return new PricingSourceRef("FOOD", "CART", cart, version, null, null);
    }
    private static Credentials credentials(CartAccess access) { return new Credentials(null, null, access.buyerNo()); }
    private IndustryOrderPort orders() {
        var value = orders.get(); if (value == null) throw unsupported(); return value;
    }
    private PricingCheckoutPort pricing() {
        var value = pricing.get(); if (value == null) throw unsupported(); return value;
    }
    private static boolean committed(Result result) {
        return result != null && result.payAmountMinor() != null && result.currency() != null
                && !result.allocations().isEmpty() && !List.of("PREPARED", "RELEASED").contains(result.status());
    }
    private static void replay(FnbOrderBatch batch, String hash, String buyer, String cart) {
        if (!hash.equals(batch.getRequestHash()) || !buyer.equals(batch.getBuyerNo())
                || !cart.equals(batch.getCartNo())) throw conflict();
    }
    private static void request(CreateDiningOrderReq req) {
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CartInputPolicy.key(req.cartNo()); CartInputPolicy.key(req.quoteNo());
        if (req.version() < 0 || req.confirmationDigest() == null
                || !req.confirmationDigest().matches("[0-9a-f]{64}") || req.payMode() == null
                || !List.of("PREPAY","POSTPAY").contains(req.payMode())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private static void buyer(CartAccess access) {
        if (access == null || access.buyerNo() == null || access.sessionNo() != null || access.accessToken() != null) {
            throw unsupported();
        }
    }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported();
    }
    private static <T> T sql(Supplier<T> read) { return DataScopeContext.executeWithoutScope(read); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
}
