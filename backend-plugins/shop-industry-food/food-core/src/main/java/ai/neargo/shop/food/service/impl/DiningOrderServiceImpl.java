package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable FOOD intent -> original core order -> immutable source allocation. No financial state is invented. */
public class DiningOrderServiceImpl implements DiningOrderService {
    private final CheckCartService carts;
    private final DiningPricingFactsService facts;
    private final StoreOperationService policies;
    private final SalesQuotaClaimService quotas;
    private final CheckCartMapper cartRows;
    private final StorePolicyMapper policyRows;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final OrderLineMapper lines;
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
        this.carts = carts; this.facts = facts; this.policies = policies; this.quotas = quotas;
        this.cartRows = cartRows; this.policyRows = policyRows; this.batches = batches; this.bindings = bindings;
        this.lines = lines; this.allocations = allocations; this.orders = orders; this.pricing = pricing;
        this.json = json; this.clock = clock; this.write = new TransactionTemplate(manager);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private record Frozen(int schema, DiningPriceFacts facts, QuoteVO quote, StoreOperationPolicyResultVO policy) { }

    @Override public DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq req,
            String key) {
        outside(); request(req); CartInputPolicy.key(store); CartInputPolicy.replayKey(key); buyer(access);
        var authorized = carts.customerGetCart(req.cartNo(), access).cart();
        if (!store.equals(authorized.storeNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var cart = sql(() -> cart(store, req.cartNo(), false));
        if (!access.buyerNo().equals(cart.getBuyerNo()) || !"H5".equals(cart.getOrderSource())
                || !"PICKUP".equals(cart.getFulfillment()) || !"PREPAY".equals(req.payMode())) throw unsupported();
        String requestKey = PricingSnapshotHash.of(List.of("FOOD_PLACEMENT_KEY_V1", access.buyerNo(), key));
        String hash = PricingSnapshotHash.of(List.of("FOOD_PLACEMENT_REQUEST_V1", store, access.buyerNo(), req));
        var existing = sql(() -> byKey(cart.getEntityNo(), store, requestKey));
        FnbOrderBatch batch;
        if (existing != null) {
            replay(existing, hash, access.buyerNo(), req.cartNo()); batch = existing;
        } else {
            var source = source(req.cartNo(), req.version());
            var sourceFacts = facts.customer(req.cartNo(), access, req.version());
            if (sourceFacts.payload().units().stream().anyMatch(u -> !"MAIN".equals(u.kind())
                    || u.componentRef() == null || !u.componentRef().matches("[0-9a-f]{64}")
                    || u.quantity().stripTrailingZeros().scale() > 0)) {
                throw unsupported();
            }
            var quote = pricing().revalidateCustomer(source, credentials(access), req.quoteNo(),
                    req.confirmationDigest());
            var projection = DiningPricingSourcePolicy.project(sourceFacts, null, source,
                    sourceFacts.payload().cart().sessionNo() == null ? access.buyerNo()
                            : sourceFacts.payload().cart().sessionNo());
            if (!quote.sourceHash().equals(projection.prices().sourceSnapshotHash())) throw conflict();
            var policy = policies.getPolicy(cart.getEntityNo(), store, new GetPolicyQuery(clock.instant()));
            if (!"PREPAY".equals(policy.payPolicy())) throw conflict();
            batch = sql(() -> write.execute(tx -> prepare(cart, req, requestKey, hash, access.buyerNo(),
                    new Frozen(1, sourceFacts, quote, policy))));
        }
        if (List.of("CANCELLED", "FAILED").contains(batch.getStatus())) return view(batch, null);
        if (binding(batch) != null) return current(batch, access.buyerNo());
        var command = new Command(identity(batch, access.buyerNo()), source(batch.getCartNo(), batch.getCartVersion()),
                credentials(access), batch.getQuoteRef(), batch.getQuoteHash(), IndustryOrderPort.Mode.PREPAY, "H5");
        final Result result;
        try { result = orders().place(command, batch.getBatchNo()); }
        catch (RuntimeException failure) {
            // A transport error does not prove the core intent absent. Keep the source and quota frozen for recovery.
            unknown(batch); throw failure;
        }
        if (!committed(result)) { unknown(batch); return view(reload(batch), result); }
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
        Result result = orders().query(identity(original, original.getBuyerNo()), original.getBatchNo());
        if (result == null) return view(original, null);
        if ("RELEASED".equals(result.status())) {
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
        if (binding(original) == null && committed(result)) bind(original, result);
        return view(reload(original), result);
    }

    private FnbOrderBatch prepare(FnbCheckCart original, CreateDiningOrderReq req, String key, String hash,
            String actor, Frozen frozen) {
        var currentPolicy = lockStore(original.getEntityNo(), original.getStoreNo());
        if (!frozen.policy().policyNo().equals(currentPolicy.getPolicyNo())
                || currentPolicy.getVersion() == null || frozen.policy().version() != currentPolicy.getVersion()) {
            throw conflict();
        }
        var cart = cart(original.getStoreNo(), original.getCartNo(), true);
        var replay = byKey(original.getEntityNo(), original.getStoreNo(), key);
        if (replay != null) { replay(replay, hash, actor, req.cartNo()); return replay; }
        if (!"OPEN".equals(cart.getStatus()) || !Objects.equals(cart.getVersion(), req.version())
                || cartRows.activePlacementCount(cart.getEntityNo(), cart.getCartNo()) != 0) throw conflict();
        var batch = new FnbOrderBatch(); stamp(batch, cart.getEntityNo(), actor);
        batch.setEntityNo(cart.getEntityNo());
        batch.setBatchNo(BizKey.next("FBAT")); batch.setStoreNo(cart.getStoreNo());
        batch.setCheckNo(cart.getCheckNo()); batch.setRequestKey(key); batch.setRequestHash(hash);
        batch.setSource(cart.getOrderSource()); batch.setBuyerNo(actor); batch.setOperatorNo(actor);
        batch.setStatus("PREPARED"); batch.setFrozenRequest(json.writeValueAsString(frozen));
        batch.setCartNo(cart.getCartNo()); batch.setCartVersion(req.version()); batch.setQuoteRef(req.quoteNo());
        batch.setQuoteHash(req.confirmationDigest()); batch.setBusinessDate(
                ServiceCalendarPolicy.businessDate(clock.instant(), frozen.policy()));
        batch.setPolicyNo(frozen.policy().policyNo()); batch.setFulfillment(cart.getFulfillment());
        batch.setPayMode(req.payMode()); batch.setActorRealm("CONSUMER");
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
            var cart = cart(original.getStoreNo(), original.getCartNo(), true);
            var batch = reload(original);
            if (binding(batch) != null) return null;
            if (!List.of("PREPARED", "PLACING", "UNKNOWN").contains(batch.getStatus())) throw conflict();
            var frozen = json.readValue(batch.getFrozenRequest(), Frozen.class);
            if (!result.quoteNo().equals(batch.getQuoteRef()) || result.sourceVersion() != batch.getCartVersion()
                    || result.payAmountMinor() != frozen.quote().totals().receivableMinor()
                    || !result.currency().equals(frozen.quote().currency())) throw conflict();
            var mapped = new HashMap<String, IndustryOrderPort.Allocation>();
            long sum = 0;
            for (var allocation : result.allocations()) {
                if (allocation.componentRef() == null || !allocation.componentRef().matches("[0-9a-f]{64}")
                        || mapped.put(allocation.sourceLineNo(), allocation) != null
                        || !result.currency().equals(allocation.currency())) throw unsupported();
                sum = Math.addExact(sum, allocation.amountMinor());
            }
            if (sum != result.payAmountMinor() || mapped.size() != frozen.facts().payload().cart().lines().size()) {
                throw conflict();
            }
            for (var input : frozen.facts().payload().cart().lines()) {
                var allocation = mapped.get(input.sourceLineRef());
                var originalUnit = frozen.facts().payload().units().stream()
                        .filter(u -> "MAIN".equals(u.kind()) && u.sourceLineNo().equals(input.sourceLineRef()))
                        .findFirst().orElseThrow(DiningOrderServiceImpl::conflict);
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
                var coreLine = new FnbCoreLineAllocation(); stamp(coreLine, batch.getEntityNo(), batch.getOperatorNo());
                coreLine.setEntityNo(batch.getEntityNo());
                coreLine.setAllocationNo(BizKey.next("FCLA")); coreLine.setFoodLineNo(line.getFoodLineNo());
                coreLine.setSourceComponentRef(allocation.componentRef());
                coreLine.setOrderItemId(allocation.orderItemId()); coreLine.setQty(input.qty());
                coreLine.setCoreSnapshotHash(allocation.hash()); coreLine.setCoreAmountMinor(allocation.amountMinor());
                coreLine.setCurrency(allocation.currency()); coreLine.setUnit("EA"); allocations.insert(coreLine);
            }
            var binding = new FnbCoreOrderBinding(); stamp(binding, batch.getEntityNo(), batch.getOperatorNo());
            binding.setEntityNo(batch.getEntityNo());
            binding.setBindingNo(BizKey.next("FCOB")); binding.setBatchNo(batch.getBatchNo());
            binding.setPaymentRootNo(result.orderNo()); binding.setStoreOrderNo(result.subOrderNo());
            binding.setStatus("BOUND"); bindings.insert(binding);
            batch.setStatus("BOUND"); batch.setUpdatedBy(batch.getOperatorNo()); batches.updateById(batch);
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
    private DiningOrderBatchVO current(FnbOrderBatch batch, String actor) {
        var bound = binding(batch);
        Result core = bound == null ? orders().query(identity(batch, actor), batch.getBatchNo())
                : orders().get(identity(batch, actor), bound.getPaymentRootNo());
        if (bound == null && committed(core)) { bind(batch, core); batch = reload(batch); }
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
                || !req.confirmationDigest().matches("[0-9a-f]{64}") || req.payMode() == null) {
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
