package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.service.PricingSourceService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.user.AdmissionPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.CloseRuleService;
import ai.neargo.shop.trade.service.IndustryOrderService;
import ai.neargo.shop.trade.service.PricingCheckoutService;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Frozen industry quote placement into the original order tables. Source/stock calls never run under order locks. */
@Service
public class IndustryOrderServiceImpl implements IndustryOrderService {
    private static final String AUTHORITY = "INDUSTRY_INVENTORY_V2";
    private final CreateIntentMapper intents;
    private final IndustryOrderSourceMapper bindings;
    private final PaymentMapper payments;
    private final StoreOrderMapper subOrders;
    private final OrderItemMapper items;
    private final OrderStatusLogMapper statusLogs;
    private final PricingPreparationService preparations;
    private final PricingCheckoutService checkout;
    private final PricingQuoteService quotes;
    private final PricingSourceService sources;
    private final OutboxEventBus events;
    private final ObjectProvider<InventoryReservationPort> inventoryProviders;
    private final DocumentNumberCoordinator numbers;
    private final GoodsQueryPort goods;
    private final MerchantScopeFactsPort ownerScopes;
    private final StoreDirectoryPort stores;
    private final MerchantQueryPort merchants;
    private final UserScopeFactsPort users;
    private final UserQueryPort userDetails;
    private final AdmissionPort admission;
    private final AttributionPort attribution;
    private final PurchaseLimitGuard purchaseLimits;
    private final CloseRuleService closeRules;
    private final PlatformTransactionManager transactionManager;
    private final JsonMapper json = JsonMapper.builder().build();
    @Value("${shop.inventory.stock-authority:PLATFORM}") private String stockAuthority;

    public IndustryOrderServiceImpl(
            CreateIntentMapper intents, IndustryOrderSourceMapper bindings, PaymentMapper payments,
            StoreOrderMapper subOrders, OrderItemMapper items, OrderStatusLogMapper statusLogs,
            PricingPreparationService preparations, PricingCheckoutService checkout,
            PricingQuoteService quotes, PricingSourceService sources, OutboxEventBus events,
            ObjectProvider<InventoryReservationPort> inventoryProviders,
            @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers,
            GoodsQueryPort goods, MerchantScopeFactsPort ownerScopes, StoreDirectoryPort stores,
            MerchantQueryPort merchants, UserScopeFactsPort users, UserQueryPort userDetails,
            AdmissionPort admission, AttributionPort attribution, PurchaseLimitGuard purchaseLimits,
            CloseRuleService closeRules,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.intents = intents;
        this.bindings = bindings;
        this.payments = payments;
        this.subOrders = subOrders;
        this.items = items;
        this.statusLogs = statusLogs;
        this.preparations = preparations;
        this.checkout = checkout;
        this.quotes = quotes;
        this.sources = sources;
        this.events = events;
        this.inventoryProviders = inventoryProviders;
        this.numbers = numbers;
        this.goods = goods;
        this.ownerScopes = ownerScopes;
        this.stores = stores;
        this.merchants = merchants;
        this.users = users;
        this.userDetails = userDetails;
        this.admission = admission;
        this.attribution = attribution;
        this.purchaseLimits = purchaseLimits;
        this.closeRules = closeRules;
        this.transactionManager = transactionManager;
    }

    @Override public Result place(Command command, String key) {
        outside(); validate(command, key);
        String hash = PricingSnapshotHash.of(command);
        var prior = intent(command.identity(), key);
        if (prior != null) {
            exact(prior, command.identity(), hash);
            var binding = binding(command.identity(), prior.getPayNo(), false);
            if ("BOUND".equals(binding.getStatus())) return result(binding);
            if (!OrdCreateIntent.PREPARED.equals(prior.getStatus())) throw conflict();
        }
        if (!"INVENTORY".equals(stockAuthority)) throw unsupported();
        var inventory = inventory();
        var prepared = preparations.customer(command.source(), command.credentials());
        authorizedSource(command, prepared);
        var quote = checkout.revalidateCustomer(command.source(), command.credentials(), command.quoteNo(),
                command.confirmationDigest());
        var guard = guards(command.identity(), prepared, quote);
        var current = preparations.confirmCustomer(command.source(), command.credentials(), prepared.preparationHash());
        authorizedSource(command, current);
        if (prior == null) prior = prepare(command, key, hash, prepared.source(), false);
        exact(prior, command.identity(), hash);
        final OrdCreateIntent claim = prior;
        // Claim against cancellation before an external reserve. Recovery owns RECOVERING and blocks placement.
        transaction(() -> {
            var locked = intents.lock(claim.getPayNo());
            if (locked == null || !OrdCreateIntent.PREPARED.equals(locked.getStatus())) throw conflict();
            locked.setNextAttemptAt(System.currentTimeMillis() + 120_000);
            update(locked); return null;
        });
        var binding = binding(command.identity(), prior.getPayNo(), false);
        List<StockPort.SkuQty> needs = needs(prior);
        var stockLines = needs.stream().map(n -> new InventoryReservationPort.Line(n.skuNo(), n.storeNo(), n.qty(),
                n.fulfillmentRef())).toList();
        var scope = inventoryScope(binding);
        var reservation = inventory.reserve(scope, stockLines, guard.minutes() * 60L, 2);
        if (reservation == null || !scope.equals(reservation.scope()) || reservation.lifecycleVersion() != 2
                || reservation.state() != InventoryReservationPort.State.HELD
                || reservation.paymentAcknowledged()) throw conflict();
        var expected = stockLines.stream().map(n -> new InventoryReservationPort.ReservedLine(n.skuNo(), n.qty(),
                n.fulfillmentRef())).toList();
        var lineOrder = Comparator.comparing(InventoryReservationPort.ReservedLine::skuNo)
                .thenComparing(InventoryReservationPort.ReservedLine::fulfillmentRef);
        if (!expected.stream().sorted(lineOrder).toList().equals(
                reservation.originalLines().stream().sorted(lineOrder).toList())) throw conflict();
        // Source is frozen by the industry placement intent; verify again after stock's independent transaction.
        checkout.revalidateCustomer(command.source(), command.credentials(), command.quoteNo(), command.confirmationDigest());
        preparations.confirmCustomer(command.source(), command.credentials(), prepared.preparationHash());
        return transaction(() -> persist(command, hash, claim.getPayNo(), prepared, quote, guard));
    }
    @Override public Result cancel(Command command, String key) {
        outside(); validate(command, key);
        String hash = PricingSnapshotHash.of(command);
        var intent = intent(command.identity(), key);
        if (intent == null) {
            // Authorize the actual source without demanding currently available rule/funding owners.
            var snapshot = sources.customer(command.source(), command.credentials());
            PricingSourceFacts.validate(snapshot);
            var identity = command.identity();
            if (!command.source().equals(snapshot.source()) || snapshot.subjectNo() == null
                    || !identity.tenantNo().equals(snapshot.scope().tenantNo())
                    || !identity.entityNo().equals(snapshot.scope().entityNo())
                    || !identity.storeNo().equals(snapshot.scope().storeNo())) throw unsupported();
            intent = prepare(command, key, hash, snapshot, true);
        }
        exact(intent, command.identity(), hash);
        var binding = binding(command.identity(), intent.getPayNo(), false);
        if ("RELEASED".equals(binding.getStatus())) return result(binding);
        if ("BOUND".equals(binding.getStatus())) throw conflict();
        String orderNo = intent.getPayNo();
        transaction(() -> {
            var locked = intents.lock(orderNo);
            exact(locked, command.identity(), hash);
            if (!Set.of(OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING).contains(locked.getStatus())) throw conflict();
            locked.setStatus(OrdCreateIntent.RECOVERING); locked.setNextAttemptAt(System.currentTimeMillis());
            update(locked); return null;
        });
        recover(command.identity(), orderNo);
        return result(binding(command.identity(), orderNo, false));
    }
    /** Trusted staff scope is authorized upstream; reads never turn that grant into buyer placement permission. */
    @Override public Result getForStore(Scope scope, String actorNo, String orderNo) {
        outside(); PricingSourceFacts.key(actorNo); PricingSourceFacts.key(orderNo);
        if (scope == null || !"MAIN".equals(scope.tenantNo())
                || !ownerScopes.entityActive(scope.tenantNo(), scope.entityNo())
                || !ownerScopes.storesOwned(scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo()))) throw unsupported();
        var binding = DataScopeContext.executeWithoutScope(() -> bindings.selectOne(
                new QueryWrapper<OrdIndustryOrderSource>().eq("tenant_no", scope.tenantNo())
                        .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                        .eq("order_no", orderNo).eq("deleted", 0)));
        if (binding == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return result(binding);
    }
    @Override public Result query(Identity identity, String key) {
        outside(); identity(identity); key(key);
        var intent = intent(identity, key);
        if (intent == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var binding = binding(identity, intent.getPayNo(), false);
        if ("BOUND".equals(binding.getStatus())) return result(binding);
        if (Set.of(OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING).contains(intent.getStatus())
                && intent.getNextAttemptAt() <= System.currentTimeMillis()) recover(identity, intent.getPayNo());
        return result(binding(identity, intent.getPayNo(), false));
    }
    @Override public Result get(Identity identity, String orderNo) {
        outside(); identity(identity); PricingSourceFacts.key(orderNo);
        return result(binding(identity, orderNo, false));
    }
    private OrdCreateIntent prepare(Command command, String key, String hash, Snapshot snapshot, boolean tombstone) {
        try {
            return numbers.transaction(Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 1), () -> {
                var order = numbers.takeReserved(DocumentType.TRADE_ORDER);
                var sub = numbers.takeReserved(DocumentType.TRADE_SUBORDER);
                var identity = command.identity();
                var quantities = new LinkedHashMap<String, Integer>();
                if (!tombstone) snapshot.prices().units().forEach(u -> quantities.merge(u.skuNo(), quantity(u.quantity()), Math::addExact));
                var needs = quantities.entrySet().stream().map(e -> new StockPort.SkuQty(e.getKey(), e.getValue(),
                        identity.storeNo(), sub.value(), 2)).toList();
                var intent = new OrdCreateIntent(); audit(intent, identity);
                intent.setPayNo(order.value()); intent.setUserNo(identity.buyerNo()); intent.setEndpoint(endpoint(identity));
                intent.setIdemKey(key); intent.setRequestHash(hash); intent.setStockAuthority(AUTHORITY);
                intent.setStockNeedsJson(json.writeValueAsString(needs));
                intent.setStatus(tombstone ? OrdCreateIntent.RELEASED : OrdCreateIntent.PREPARED);
                intent.setNextAttemptAt(System.currentTimeMillis() + 120_000); intents.insert(intent);
                var binding = new OrdIndustryOrderSource(); audit(binding, identity);
                binding.setOrderNo(order.value()); binding.setSubOrderNo(sub.value());
                binding.setEntityNo(identity.entityNo()); binding.setStoreNo(identity.storeNo());
                binding.setBuyerNo(identity.buyerNo()); binding.setActorNo(identity.actorNo());
                binding.setSourceSystem(command.source().sourceSystem()); binding.setSourceType(command.source().sourceType());
                binding.setSourceNo(command.source().sourceNo()); binding.setSourceVersion(command.source().sourceVersion());
                binding.setSourceHash(snapshot.snapshotHash()); binding.setSourceSubjectNo(snapshot.subjectNo()); binding.setQuoteNo(command.quoteNo());
                binding.setConfirmationDigest(command.confirmationDigest()); binding.setRequestHash(hash);
                binding.setOrderMode(command.mode().name()); binding.setStatus(tombstone ? "RELEASED" : "PREPARED"); bindings.insert(binding);
                numbers.bindReserved(new DocumentSource(identity.tenantNo(), "CORE", "TRADE_ORDER", order.value()), order);
                numbers.bindReserved(new DocumentSource(identity.tenantNo(), "CORE", "TRADE_SUBORDER", sub.value()), sub);
                return intent;
            });
        } catch (DuplicateKeyException concurrent) {
            var existing = intent(command.identity(), key);
            if (existing == null) throw conflict();
            exact(existing, command.identity(), hash); return existing;
        }
    }
    private Result persist(Command command, String hash, String orderNo, Prepared prepared,
            PricingCheckoutPort.QuoteVO quote, Guard guard) {
        var intent = intents.lock(orderNo);
        exact(intent, command.identity(), hash);
        var binding = binding(command.identity(), orderNo, true);
        if ("BOUND".equals(binding.getStatus())) return result(binding);
        if (!OrdCreateIntent.PREPARED.equals(intent.getStatus()) || !binding.getSourceHash().equals(
                prepared.source().snapshotHash()) || !binding.getRequestHash().equals(hash)) throw conflict();
        var identity = command.identity();
        var order = new OrdOrder(); audit(order, identity);
        order.setOrderNo(orderNo); order.setUserNo(identity.buyerNo()); order.setCommunityNo(guard.communityNo());
        order.setGoodsAmount(quote.totals().goodsMinor()); order.setFreightAmount(0L); order.setDiscountAmount(0L);
        order.setPayAmount(quote.totals().receivableMinor()); order.setCurrency(quote.currency());
        order.setStatus(OrdOrder.WAIT_PAY); order.setPayScene(PayScenes.normalize(command.payScene()));
        order.setPointsIntegrated(false); order.setPayDeadlineAt(System.currentTimeMillis() + guard.minutes() * 60_000L);
        payments.insert(order);
        var sub = new OrdSubOrder(); audit(sub, identity);
        sub.setOrderNo(orderNo); sub.setSubOrderNo(binding.getSubOrderNo()); sub.setUserNo(identity.buyerNo());
        sub.setEntityNo(identity.entityNo()); sub.setStoreNo(identity.storeNo()); sub.setEntityName(guard.entityName());
        sub.setCommunityNo(guard.communityNo()); sub.setFulfillment(Fulfillments.STORE_PICKUP);
        sub.setInventoryLifecycleVersion(2); sub.setTrafficSource(guard.trafficSource());
        sub.setGoodsAmount(order.getGoodsAmount()); sub.setFreightAmount(0L); sub.setDiscountAmount(0L);
        sub.setDiscountPlatform(0L); sub.setDiscountMerchant(0L); sub.setPointsDeduct(0); sub.setPointsDeductMinor(0L);
        sub.setPayAmount(order.getPayAmount()); sub.setStatus(OrdSubOrder.WAIT_PAY);
        sub.setRequireBuyerConfirm(guard.buyerConfirm() ? 1 : 0); sub.setBuyerNickname(guard.buyerNickname());
        subOrders.insert(sub);
        var allocations = new ArrayList<Allocation>();
        var evidence = new ArrayList<Object>();
        for (int index = 0; index < prepared.source().prices().units().size(); index++) {
            var unit = prepared.source().prices().units().get(index);
            var priced = quote.lines().get(index); var sku = guard.skus().get(unit.skuNo());
            var item = new OrdItem(); audit(item, identity);
            item.setOrderNo(orderNo); item.setSubOrderNo(sub.getSubOrderNo()); item.setGoodsNo(unit.goodsNo());
            item.setSkuNo(unit.skuNo()); item.setTitle(sku.title()); item.setCover(sku.cover()); item.setSpec(sku.spec());
            item.setPrice(unit.unitPrice().minor()); item.setQty(quantity(unit.quantity())); item.setAmount(unit.baseAmount().minor());
            item.setPaidShareMinor(priced.receivableMinor()); item.setCategoryType(sku.categoryType());
            item.setCategoryNo(sku.categoryNo()); item.setSupplyStoreNo(identity.storeNo()); item.setShippedQty(0);
            item.setIsGift(false); item.setWeighed(false); item.setRefundedQty(0); item.setRefundedMinor(0L); items.insert(item);
            if (item.getId() == null) throw conflict();
            String digest = PricingSnapshotHash.of(List.of("INDUSTRY_ITEM_ALLOCATION_V1", orderNo, item.getId(), unit, priced));
            allocations.add(new Allocation(unit.sourceLineNo(), unit.componentRef(), item.getId(), item.getQty(),
                    priced.receivableMinor(), quote.currency(), digest));
            evidence.add(Map.of("allocation", allocations.getLast(), "source", unit, "price", priced));
        }
        // Existing quote service joins this transaction: quote retain + original rows + binding commit atomically.
        var retained = quotes.retain(new Context(identity.tenantNo(), identity.entityNo(), identity.storeNo(),
                prepared.source().subjectNo(), identity.actorNo()), command.quoteNo(), orderNo);
        if (retained == null || !orderNo.equals(retained.retainedRef())) throw conflict();
        binding.setEconomicsHash(retained.economicsHash());
        binding.setAllocationsJson(json.writeValueAsString(new BoundAllocations(allocations, evidence)));
        binding.setStatus("BOUND"); binding.setUpdatedAt(LocalDateTime.now()); update(binding);
        intent.setStatus(OrdCreateIntent.COMMITTED); intent.setUpdatedAt(LocalDateTime.now()); update(intent);
        var log = new OrdStatusLog(); log.setTenantNo(identity.tenantNo()); log.setSubOrderNo(sub.getSubOrderNo());
        log.setAggregateType("ORDER"); log.setAggregateNo(sub.getSubOrderNo()); log.setStatus(OrdSubOrder.WAIT_PAY);
        log.setLabel("已下单，待付款"); log.setOperatorType(OrdStatusLog.BY_USER); log.setOperatorNo(identity.actorNo());
        log.setAt(System.currentTimeMillis()); log.setCreatedAt(LocalDateTime.now()); statusLogs.insert(log);
        events.publish(new OrderEvents.OrderCreated(orderNo, identity.buyerNo(), List.of(sub.getSubOrderNo()), order.getPayAmount()));
        return new Result(orderNo, sub.getSubOrderNo(), command.quoteNo(), order.getStatus(), order.getCurrency(),
                order.getPayAmount(), command.source().sourceVersion(), 2, allocations);
    }
    private Guard guards(Identity identity, Prepared prepared, PricingCheckoutPort.QuoteVO quote) {
        if (!ownerScopes.entityActive(identity.tenantNo(), identity.entityNo())
                || !ownerScopes.storesOwned(identity.tenantNo(), identity.entityNo(), Set.of(identity.storeNo()))) throw unsupported();
        var card = stores.cards(List.of(identity.storeNo())).get(identity.storeNo());
        if (card == null || !card.active() || !identity.entityNo().equals(card.entityNo())) throw unsupported();
        var buyer = users.findActive(identity.tenantNo(), identity.buyerNo()).orElseThrow(IndustryOrderServiceImpl::unsupported);
        var merchant = merchants.find(identity.entityNo()).filter(m -> m.canSell() && m.canReceive())
                .orElseThrow(IndustryOrderServiceImpl::unsupported);
        var units = prepared.source().prices().units();
        var snapshots = goods.snapshot(units.stream().map(u -> u.skuNo()).distinct().toList(),
                Map.of(identity.entityNo(), identity.storeNo()));
        var quantities = new HashMap<String, Integer>(); var limits = new HashMap<String, Integer>();
        for (var unit : units) {
            int qty = quantity(unit.quantity()); var sku = snapshots.get(unit.skuNo());
            if (sku == null || !unit.goodsNo().equals(sku.goodsNo()) || !unit.skuNo().equals(sku.skuNo())
                    || !identity.entityNo().equals(sku.merchantNo()) || !identity.storeNo().equals(sku.storeNo())
                    || !sku.onSale() || sku.fulfillments() == null || !sku.fulfillments().contains(Fulfillments.STORE_PICKUP)
                    || sku.activityOnly() || !"STANDARD".equals(sku.categoryType())
                    || sku.saleMode() != null && !"NORMAL".equals(sku.saleMode())) throw unsupported();
            quantities.merge(unit.goodsNo(), qty, Math::addExact);
            if (sku.limitPerUser() != null && sku.limitPerUser() > 0) limits.put(unit.goodsNo(), sku.limitPerUser());
        }
        purchaseLimits.require(identity.buyerNo(), quantities, limits);
        admission.requireOrderAllowed(identity.entityNo(), quote.totals().receivableMinor(), () -> paidToday(identity));
        boolean buyerConfirm = admission.requireFulfillmentAllowed(identity.entityNo(), Fulfillments.STORE_PICKUP, null);
        var pay = merchants.payCapabilityOf(identity.entityNo(), identity.storeNo());
        if (pay == null || pay.payMethods() == null || pay.payMethods().isEmpty()) throw unsupported();
        if (pay.wouldExceed(quote.totals().receivableMinor())) throw BizException.of(ErrorCode.MERCHANT_QUOTA_EXHAUSTED);
        int minutes = closeRules.unpaidMinutes();
        if (minutes < CloseRuleService.MIN_UNPAID_MINUTES || minutes > CloseRuleService.MAX_UNPAID_MINUTES) throw unsupported();
        String traffic = attribution.resolveTrafficSource(identity.buyerNo(), identity.entityNo());
        if (!Set.of(OrdSubOrder.TRAFFIC_MERCHANT_OWNED, OrdSubOrder.TRAFFIC_PLATFORM).contains(traffic)) throw unsupported();
        if (!"CNY".equals(quote.currency()) || quote.totals().discountMinor() != 0
                || quote.totals().receivableMinor() < 0 || quote.lines().size() != units.size()) throw unsupported();
        return new Guard(snapshots, merchant.merchantName(), buyer.nickname(), buyerConfirm, traffic,
                userDetails.communityOf(identity.buyerNo()).orElse(null), minutes);
    }
    private long paidToday(Identity identity) {
        return DataScopeContext.executeWithoutScope(() -> subOrders.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getTenantNo, identity.tenantNo()).eq(OrdSubOrder::getEntityNo, identity.entityNo())
                .ge(OrdSubOrder::getCreatedAt, LocalDate.now().atStartOfDay())
                .notIn(OrdSubOrder::getStatus, OrdSubOrder.WAIT_PAY, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED)))
                .stream().mapToLong(s -> s.getPayAmount() == null ? 0 : s.getPayAmount()).reduce(0, Math::addExact);
    }
    private void authorizedSource(Command command, Prepared prepared) {
        var identity = command.identity(); var source = prepared.source();
        if (!command.source().equals(source.source()) || !identity.tenantNo().equals(source.scope().tenantNo())
                || !identity.entityNo().equals(source.scope().entityNo()) || !identity.storeNo().equals(source.scope().storeNo())
                || source.subjectNo() == null) throw unsupported();
        if (source.composition().stream().anyMatch(c -> c.kind() != Kind.MAIN || c.unit().componentRef() == null
                || !c.unit().componentRef().matches("[0-9a-f]{64}"))) throw unsupported();
        source.prices().units().forEach(u -> quantity(u.quantity()));
    }
    private void recover(Identity identity, String orderNo) {
        boolean claim = transaction(() -> {
            var intent = intents.lock(orderNo);
            if (intent == null || !Set.of(OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING).contains(intent.getStatus())
                    || intent.getNextAttemptAt() > System.currentTimeMillis()) return false;
            intent.setStatus(OrdCreateIntent.RECOVERING); intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
            update(intent); return true;
        });
        if (!claim) return;
        var source = binding(identity, orderNo, false);
        var cancelled = inventory().cancelAll(inventoryScope(source));
        if (cancelled == null || !inventoryScope(source).equals(cancelled.scope())
                || cancelled.state() != InventoryReservationPort.State.RELEASED
                && cancelled.state() != InventoryReservationPort.State.EXPIRED) throw conflict();
        transaction(() -> {
            var intent = intents.lock(orderNo);
            if (!OrdCreateIntent.RECOVERING.equals(intent.getStatus())) throw conflict();
            intent.setStatus(OrdCreateIntent.RELEASED); update(intent);
            var locked = binding(identity, orderNo, true); locked.setStatus("RELEASED"); update(locked); return null;
        });
    }
    private Result result(OrdIndustryOrderSource binding) {
        if (!"BOUND".equals(binding.getStatus())) return new Result(binding.getOrderNo(), binding.getSubOrderNo(),
                binding.getQuoteNo(), binding.getStatus(), null, null, binding.getSourceVersion(), 2, List.of());
        var order = DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getTenantNo, binding.getTenantNo()).eq(OrdOrder::getOrderNo, binding.getOrderNo())
                .eq(OrdOrder::getUserNo, binding.getBuyerNo()).eq(OrdOrder::getDeleted, 0)));
        if (order == null) throw conflict();
        var allocations = json.readValue(binding.getAllocationsJson(), BoundAllocations.class).allocations();
        return new Result(order.getOrderNo(), binding.getSubOrderNo(), binding.getQuoteNo(), order.getStatus(),
                order.getCurrency(), order.getPayAmount(), binding.getSourceVersion(), 2, allocations);
    }
    private OrdCreateIntent intent(Identity identity, String key) {
        return DataScopeContext.executeWithoutScope(() -> intents.selectOne(new QueryWrapper<OrdCreateIntent>()
                .eq("tenant_no", identity.tenantNo()).eq("endpoint", endpoint(identity)).eq("idem_key", key)
                .eq("user_no", identity.buyerNo()).eq("stock_authority", AUTHORITY).eq("deleted", 0)));
    }
    private OrdIndustryOrderSource binding(Identity identity, String orderNo, boolean lock) {
        var query = new QueryWrapper<OrdIndustryOrderSource>().eq("tenant_no", identity.tenantNo())
                .eq("entity_no", identity.entityNo()).eq("store_no", identity.storeNo()).eq("buyer_no", identity.buyerNo())
                .eq("actor_no", identity.actorNo()).eq("order_no", orderNo).eq("deleted", 0);
        if (lock) query.last("FOR UPDATE");
        var result = DataScopeContext.executeWithoutScope(() -> bindings.selectOne(query));
        if (result == null) throw BizException.of(ErrorCode.NOT_FOUND); return result;
    }
    private List<StockPort.SkuQty> needs(OrdCreateIntent intent) {
        return json.readValue(intent.getStockNeedsJson(), new TypeReference<>() { });
    }
    private InventoryReservationPort inventory() {
        var candidates = inventoryProviders.orderedStream().limit(2).toList();
        if (candidates.size() != 1) throw unsupported(); return candidates.getFirst();
    }
    private InventoryReservationPort.Scope inventoryScope(OrdIndustryOrderSource binding) {
        return new InventoryReservationPort.Scope(binding.getTenantNo(), binding.getEntityNo(), binding.getOrderNo());
    }
    private <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> DataScopeContext.executeWithoutScope(action));
    }
    private void update(OrdCreateIntent intent) { if (intents.updateById(intent) != 1) throw conflict(); }
    private void update(OrdIndustryOrderSource source) { if (bindings.updateById(source) != 1) throw conflict(); }
    private static void validate(Command command, String key) {
        if (command == null) throw bad(); identity(command.identity()); key(key);
        if (command.mode() != Mode.PREPAY || command.credentials() == null || command.source() == null
                || !"FOOD".equals(command.source().sourceSystem()) || !"CART".equals(command.source().sourceType())
                || command.source().scopeNo() != null || command.credentials().sessionNo() != null
                || command.credentials().accessToken() != null
                || !command.identity().buyerNo().equals(command.credentials().buyerNo())) throw unsupported();
        PricingSourceFacts.key(command.quoteNo());
        if (command.confirmationDigest() == null || !command.confirmationDigest().matches("[0-9a-f]{64}")) throw bad();
    }
    private static void identity(Identity identity) {
        if (identity == null) throw bad();
        try {
            PricingSourceFacts.key(identity.tenantNo()); PricingSourceFacts.key(identity.entityNo());
            PricingSourceFacts.key(identity.storeNo()); PricingSourceFacts.key(identity.buyerNo());
            PricingSourceFacts.key(identity.actorNo());
        } catch (IllegalArgumentException invalid) { throw bad(); }
        if (!"MAIN".equals(identity.tenantNo()) || !identity.buyerNo().equals(identity.actorNo())) throw unsupported();
    }
    private static void exact(OrdCreateIntent intent, Identity identity, String hash) {
        if (intent == null || !identity.buyerNo().equals(intent.getUserNo()) || !hash.equals(intent.getRequestHash())
                || !AUTHORITY.equals(intent.getStockAuthority())) throw conflict();
    }
    private static String endpoint(Identity identity) { return "INDUSTRY_ORDER:" + PricingSnapshotHash.of(identity); }
    private static Context quoteContext(Identity identity) {
        return new Context(identity.tenantNo(), identity.entityNo(), identity.storeNo(), identity.buyerNo(), identity.actorNo());
    }
    private static int quantity(BigDecimal quantity) {
        try { int value = quantity.intValueExact(); if (value <= 0) throw unsupported(); return value; }
        catch (ArithmeticException invalid) { throw unsupported(); }
    }
    private static void audit(BaseEntity row, Identity identity) {
        row.setTenantNo(identity.tenantNo()); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
        row.setCreatedBy(identity.actorNo()); row.setUpdatedBy(identity.actorNo());
    }
    private static void key(String key) { if (key == null || key.isBlank() || key.length() > 128) throw bad(); }
    private static void outside() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported(); }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private record Guard(Map<String, SkuSnapshot> skus, String entityName, String buyerNickname,
            boolean buyerConfirm, String trafficSource, String communityNo, int minutes) { }
    private record BoundAllocations(List<Allocation> allocations, List<Object> evidence) { }
}
