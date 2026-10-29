package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderCompositionFacts;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingFeeFacts;
import ai.neargo.shop.spi.trade.PricingFeeRulePort;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.AdmissionPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.CloseRuleService;
import ai.neargo.shop.trade.service.IndustryChannelOrderService;
import ai.neargo.shop.trade.service.IndustryOrderAmendmentService;
import ai.neargo.shop.trade.service.IndustryOrderService;
import ai.neargo.shop.trade.service.PricingCheckoutService;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import ai.neargo.shop.trade.service.PricingSourceService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Frozen industry quote placement into the original order tables. Source/stock calls never run
 * under order locks.
 */
@Service
public class IndustryOrderServiceImpl implements IndustryOrderService {
    private static final String AUTHORITY = "INDUSTRY_INVENTORY_V2";
    private ObjectProvider<InventoryDemandPlanPort> demandProviders;
    private IndustryChannelOrderService channelOrders;

    /** CHANNEL reads use their own source quote and receipt, never ordinary checkout amendments. */
    @Autowired(required = false)
    public void channelOrderProjection(IndustryChannelOrderService service) {
        this.channelOrders = service;
    }

    private ObjectProvider<PricingFeeRulePort> feeProviders;
    private IndustryOrderAmendmentMapper amendmentRows;
    private IndustryOrderAmendmentService amendments;

    @Autowired(required = false)
    public void amendmentSourceProjection(IndustryOrderAmendmentService service) {
        this.amendments = service;
    }

    @Autowired(required = false)
    public void amendmentProjection(IndustryOrderAmendmentMapper mapper) {
        this.amendmentRows = mapper;
    }

    @Autowired
    public void pricingFeeProviders(ObjectProvider<PricingFeeRulePort> providers) {
        this.feeProviders = providers;
    }

    @Autowired
    public void demandPlanProviders(ObjectProvider<InventoryDemandPlanPort> providers) {
        this.demandProviders = providers;
    }

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

    @Value("${shop.inventory.stock-authority:PLATFORM}")
    private String stockAuthority;

    public IndustryOrderServiceImpl(
            CreateIntentMapper intents,
            IndustryOrderSourceMapper bindings,
            PaymentMapper payments,
            StoreOrderMapper subOrders,
            OrderItemMapper items,
            OrderStatusLogMapper statusLogs,
            PricingPreparationService preparations,
            PricingCheckoutService checkout,
            PricingQuoteService quotes,
            PricingSourceService sources,
            OutboxEventBus events,
            ObjectProvider<InventoryReservationPort> inventoryProviders,
            @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers,
            GoodsQueryPort goods,
            MerchantScopeFactsPort ownerScopes,
            StoreDirectoryPort stores,
            MerchantQueryPort merchants,
            UserScopeFactsPort users,
            UserQueryPort userDetails,
            AdmissionPort admission,
            AttributionPort attribution,
            PurchaseLimitGuard purchaseLimits,
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

    @Override
    public Result place(Command command, String key) {
        outside();
        validate(command, key);
        return placeOriginal(command, key, false, Fulfillments.STORE_PICKUP);
    }

    /** Staff source resolution never creates consumer credentials or grants payment consent. */
    @Override
    public Result placeForStore(Command command, String key) {
        outside();
        validateStaff(command, key);
        return placeOriginal(command, key, true, Fulfillments.STORE_PICKUP);
    }

    /**
     * Dine-in retains the original command schema while giving its distinct fulfillment an
     * immutable request identity.
     */
    @Override
    public Result placeForDiningRoom(Command command, String key) {
        outside();
        validateStaff(command, key);
        return placeOriginal(command, key, true, Fulfillments.DINE_IN);
    }

    @Override
    public Result placeForService(Command command, String key, String fulfillment, boolean staff) {
        outside();
        if (staff) validateStaff(command, key);
        else validate(command, key);
        if (command.mode() != Mode.POSTPAY
                || !Set.of(Fulfillments.DINE_IN, Fulfillments.MERCHANT_DELIVERY)
                        .contains(Objects.toString(fulfillment, ""))) throw unsupported();
        return placeOriginal(command, key, staff, fulfillment);
    }

    private static String placementHash(Command command, String fulfillment) {
        if (Fulfillments.DINE_IN.equals(fulfillment))
            return PricingSnapshotHash.of(List.of("INDUSTRY_DINE_IN_PLACEMENT_V1", command));
        if (Fulfillments.MERCHANT_DELIVERY.equals(fulfillment))
            return PricingSnapshotHash.of(List.of("INDUSTRY_DELIVERY_PLACEMENT_V1", command));
        return PricingSnapshotHash.of(command);
    }

    private Result placeOriginal(Command command, String key, boolean staff, String fulfillment) {
        String hash = placementHash(command, fulfillment);
        var prior = intent(command.identity(), key);
        if (prior != null) {
            exact(prior, command.identity(), hash);
            var binding = binding(command.identity(), prior.getPayNo(), false);
            if ("ACCEPTED".equals(binding.getStatus())) return result(binding);
            if ("BOUND".equals(binding.getStatus()))
                return "POSTPAY".equals(binding.getOrderMode())
                        ? acceptUnpaidOriginal(command.identity(), binding.getOrderNo())
                        : result(binding);
            if (!OrdCreateIntent.PREPARED.equals(prior.getStatus())) throw conflict();
        }
        if (!"INVENTORY".equals(stockAuthority)) throw unsupported();
        var prepared = prepareSource(command, staff);
        authorizedSource(command, prepared, staff);
        var quote = revalidate(command, staff);
        var party = command.identity().partyNo() == null ? null : sources.party(command);
        if (party != null && command.mode() != Mode.POSTPAY) throw unsupported();
        var delivery =
                Fulfillments.MERCHANT_DELIVERY.equals(fulfillment)
                        ? sources.delivery(command)
                        : null;
        var guard =
                guards(
                        command.identity(),
                        prepared,
                        quote,
                        command.mode(),
                        fulfillment,
                        party,
                        delivery);
        var current = confirmSource(command, prepared.preparationHash(), staff);
        authorizedSource(command, current, staff);
        if (prior == null) prior = prepare(command, key, hash, prepared.source(), false);
        exact(prior, command.identity(), hash);
        final OrdCreateIntent claim = prior;
        // Claim against cancellation before an external reserve. Recovery owns RECOVERING and
        // blocks placement.
        transaction(
                () -> {
                    var locked = intents.lock(claim.getPayNo());
                    if (locked == null || !OrdCreateIntent.PREPARED.equals(locked.getStatus()))
                        throw conflict();
                    locked.setNextAttemptAt(System.currentTimeMillis() + 120_000);
                    update(locked);
                    return null;
                });
        var binding = binding(command.identity(), prior.getPayNo(), false);
        Plan exactPlan = frozenPlan(command, binding, prepared.source());
        if (exactPlan != null) {
            var held =
                    demands()
                            .reserve(
                                    exactPlan,
                                    guard.minutes() * 60L,
                                    command.mode() == Mode.POSTPAY ? 3 : 2);
            demandReceipt(exactPlan, held, Set.of("HELD"));
        } else {
            var inventory = inventory();
            List<StockPort.SkuQty> needs = needs(prior);
            var stockLines =
                    needs.stream()
                            .map(
                                    n ->
                                            new InventoryReservationPort.Line(
                                                    n.skuNo(),
                                                    n.storeNo(),
                                                    n.qty(),
                                                    n.fulfillmentRef()))
                            .toList();
            var scope = inventoryScope(binding);
            int lifecycle = command.mode() == Mode.POSTPAY ? 3 : 2;
            var reservation =
                    inventory.reserve(scope, stockLines, guard.minutes() * 60L, lifecycle);
            if (reservation == null
                    || !scope.equals(reservation.scope())
                    || reservation.lifecycleVersion() != lifecycle
                    || reservation.state() != InventoryReservationPort.State.HELD
                    || reservation.paymentAcknowledged()) throw conflict();
            var expected =
                    stockLines.stream()
                            .map(
                                    n ->
                                            new InventoryReservationPort.ReservedLine(
                                                    n.skuNo(), n.qty(), n.fulfillmentRef()))
                            .toList();
            var lineOrder =
                    Comparator.comparing(InventoryReservationPort.ReservedLine::skuNo)
                            .thenComparing(InventoryReservationPort.ReservedLine::fulfillmentRef);
            if (!expected.stream()
                    .sorted(lineOrder)
                    .toList()
                    .equals(reservation.originalLines().stream().sorted(lineOrder).toList()))
                throw conflict();
        }
        // Source is frozen by the industry placement intent; verify again after stock's independent
        // transaction.
        revalidate(command, staff);
        confirmSource(command, prepared.preparationHash(), staff);
        Result created =
                transaction(
                        () ->
                                persist(
                                        command,
                                        hash,
                                        claim.getPayNo(),
                                        prepared,
                                        quote,
                                        guard,
                                        fulfillment));
        return command.mode() == Mode.POSTPAY
                ? acceptUnpaidOriginal(command.identity(), created.orderNo())
                : created;
    }

    @Override
    public Result acceptUnpaid(Identity identity, String orderNo) {
        outside();
        identity(identity);
        PricingSourceFacts.key(orderNo);
        return acceptUnpaidOriginal(identity, orderNo);
    }

    private Result acceptUnpaidOriginal(Identity identity, String orderNo) {
        var source = binding(identity, orderNo, false);
        if (!"POSTPAY".equals(source.getOrderMode())) throw unsupported();
        if ("ACCEPTED".equals(source.getStatus())) return result(source);
        if (!"BOUND".equals(source.getStatus())) throw conflict();
        var intent =
                DataScopeContext.executeWithoutScope(
                        () ->
                                intents.selectOne(
                                        Wrappers.<OrdCreateIntent>query()
                                                .eq("tenant_no", identity.tenantNo())
                                                .eq("pay_no", orderNo)
                                                .eq("user_no", identity.buyerNo())
                                                .eq("stock_authority", AUTHORITY)));
        if (intent == null
                || !OrdCreateIntent.COMMITTED.equals(intent.getStatus())
                || !source.getRequestHash().equals(intent.getRequestHash())) throw conflict();
        var scope = inventoryScope(source);
        if (source.getDemandPlanSnapshot() != null) {
            var plan = json.readValue(source.getDemandPlanSnapshot(), Plan.class);
            var accepted = demands().acceptUnpaid(scope);
            demandReceipt(
                    plan, accepted, Set.of("ACCEPTED_UNPAID", "PARTIAL_UNPAID", "ISSUED_UNPAID"));
            if (accepted.lifecycleVersion() != 3) throw conflict();
        } else {
            var actual = inventory().acceptUnpaid(scope);
            if (actual == null
                    || !scope.equals(actual.scope())
                    || actual.lifecycleVersion() != 3
                    || actual.paymentAcknowledged()
                    || !Set.of(
                                    InventoryReservationPort.State.ACCEPTED_UNPAID,
                                    InventoryReservationPort.State.PARTIAL_UNPAID,
                                    InventoryReservationPort.State.ISSUED_UNPAID)
                            .contains(actual.state())) throw conflict();
            var originalNeeds = needs(intent);
            if (originalNeeds.stream()
                    .anyMatch(
                            n ->
                                    n.lifecycleVersion() != 3
                                            || !identity.storeNo().equals(n.storeNo())
                                            || !source.getSubOrderNo().equals(n.fulfillmentRef())))
                throw conflict();
            var expected =
                    originalNeeds.stream()
                            .map(
                                    n ->
                                            new InventoryReservationPort.ReservedLine(
                                                    n.skuNo(), n.qty(), n.fulfillmentRef()))
                            .sorted(
                                    Comparator.comparing(
                                                    InventoryReservationPort.ReservedLine::skuNo)
                                            .thenComparing(
                                                    InventoryReservationPort.ReservedLine
                                                            ::fulfillmentRef))
                            .toList();
            var received =
                    actual.originalLines().stream()
                            .sorted(
                                    Comparator.comparing(
                                                    InventoryReservationPort.ReservedLine::skuNo)
                                            .thenComparing(
                                                    InventoryReservationPort.ReservedLine
                                                            ::fulfillmentRef))
                            .toList();
            if (expected.isEmpty() || !expected.equals(received)) throw conflict();
        }
        return transaction(
                () -> {
                    var order =
                            payments.selectOne(
                                    Wrappers.<OrdOrder>lambdaQuery()
                                            .eq(OrdOrder::getTenantNo, identity.tenantNo())
                                            .eq(OrdOrder::getOrderNo, orderNo)
                                            .eq(OrdOrder::getUserNo, identity.buyerNo())
                                            .last("FOR UPDATE"));
                    var locked = binding(identity, orderNo, true);
                    if ("ACCEPTED".equals(locked.getStatus())) return result(locked);
                    if (!"BOUND".equals(locked.getStatus())
                            || !"POSTPAY".equals(locked.getOrderMode())
                            || !locked.getRequestHash().equals(intent.getRequestHash())
                            || order == null
                            || !OrdOrder.WAIT_PAY.equals(order.getStatus())
                            || order.getPaidAt() != null) throw conflict();
                    var sub =
                            subOrders.selectOne(
                                    Wrappers.<OrdSubOrder>lambdaQuery()
                                            .eq(OrdSubOrder::getTenantNo, identity.tenantNo())
                                            .eq(OrdSubOrder::getEntityNo, identity.entityNo())
                                            .eq(OrdSubOrder::getStoreNo, identity.storeNo())
                                            .eq(OrdSubOrder::getOrderNo, orderNo)
                                            .eq(OrdSubOrder::getSubOrderNo, locked.getSubOrderNo())
                                            .last("FOR UPDATE"));
                    if (sub == null
                            || !Integer.valueOf(3).equals(sub.getInventoryLifecycleVersion())
                            || !Set.of(
                                            OrdSubOrder.WAIT_PAY,
                                            OrdSubOrder.WAIT_FULFILL,
                                            OrdSubOrder.FULFILLING,
                                            OrdSubOrder.COMPLETED)
                                    .contains(sub.getStatus())) throw conflict();
                    if (OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) {
                        sub.setStatus(OrdSubOrder.WAIT_FULFILL);
                        sub.setUpdatedBy(identity.actorNo());
                        if (sub.getVerifyCode() == null)
                            sub.setVerifyCode(
                                    OrderServiceImpl.originalOwnerUnusedVerifyCode(subOrders));
                        if (subOrders.updateById(sub) != 1) throw conflict();
                        var log = new OrdStatusLog();
                        log.setTenantNo(identity.tenantNo());
                        log.setSubOrderNo(sub.getSubOrderNo());
                        log.setAggregateType("ORDER");
                        log.setAggregateNo(sub.getSubOrderNo());
                        log.setEvent("ACCEPT_UNPAID");
                        log.setFromStatus(OrdSubOrder.WAIT_PAY);
                        log.setStatus(OrdSubOrder.WAIT_FULFILL);
                        log.setLabel("已接单，待履约（未收款）");
                        log.setOperatorType(OrdStatusLog.BY_USER);
                        log.setOperatorNo(identity.actorNo());
                        log.setAt(System.currentTimeMillis());
                        log.setCreatedAt(LocalDateTime.now());
                        statusLogs.insert(log);
                    }
                    locked.setStatus("ACCEPTED");
                    locked.setUpdatedAt(LocalDateTime.now());
                    update(locked);
                    return result(locked);
                });
    }

    @Override
    public Result cancel(Command command, String key) {
        outside();
        validate(command, key);
        return cancelOriginal(command, key, false, Fulfillments.STORE_PICKUP);
    }

    @Override
    public Result cancelForStore(Command command, String key, String fulfillment) {
        outside();
        validateStaff(command, key);
        if (!Set.of(Fulfillments.STORE_PICKUP, Fulfillments.DINE_IN, Fulfillments.MERCHANT_DELIVERY)
                .contains(Objects.toString(fulfillment, ""))) throw unsupported();
        return cancelOriginal(command, key, true, fulfillment);
    }

    private Result cancelOriginal(Command command, String key, boolean staff, String fulfillment) {
        String hash = placementHash(command, fulfillment);
        var intent = intent(command.identity(), key);
        if (intent == null) {
            // Authorize the actual source without demanding currently available rule/funding
            // owners.
            var snapshot =
                    staff
                            ? sources.cancellationForStore(command, key, fulfillment)
                            : sources.customer(command.source(), command.credentials());
            PricingSourceFacts.validate(snapshot);
            var identity = command.identity();
            if (!command.source().equals(snapshot.source())
                    || !staff && snapshot.subjectNo() == null
                    || staff
                            && (snapshot.subjectNo() != null
                                    || !Set.of("POS", "WAITER").contains(snapshot.channel()))
                    || !identity.tenantNo().equals(snapshot.scope().tenantNo())
                    || !identity.entityNo().equals(snapshot.scope().entityNo())
                    || !identity.storeNo().equals(snapshot.scope().storeNo())) throw unsupported();
            intent = prepare(command, key, hash, snapshot, true);
        }
        exact(intent, command.identity(), hash);
        var binding = binding(command.identity(), intent.getPayNo(), false);
        if ("RELEASED".equals(binding.getStatus())) return result(binding);
        if (Set.of("BOUND", "ACCEPTED").contains(binding.getStatus())) throw conflict();
        String orderNo = intent.getPayNo();
        transaction(
                () -> {
                    var locked = intents.lock(orderNo);
                    exact(locked, command.identity(), hash);
                    if (!Set.of(OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING)
                            .contains(locked.getStatus())) throw conflict();
                    locked.setStatus(OrdCreateIntent.RECOVERING);
                    locked.setNextAttemptAt(System.currentTimeMillis());
                    update(locked);
                    return null;
                });
        recover(command.identity(), orderNo);
        return result(binding(command.identity(), orderNo, false));
    }

    /**
     * Trusted staff scope is authorized upstream; reads never turn that grant into buyer placement
     * permission.
     */
    @Override
    public Result getForStore(Scope scope, String actorNo, String orderNo) {
        outside();
        PricingSourceFacts.key(actorNo);
        PricingSourceFacts.key(orderNo);
        if (scope == null
                || !"MAIN".equals(scope.tenantNo())
                || !ownerScopes.entityActive(scope.tenantNo(), scope.entityNo())
                || !ownerScopes.storesOwned(
                        scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo())))
            throw unsupported();
        var binding =
                DataScopeContext.executeWithoutScope(
                        () ->
                                bindings.selectOne(
                                        new QueryWrapper<OrdIndustryOrderSource>()
                                                .eq("tenant_no", scope.tenantNo())
                                                .eq("entity_no", scope.entityNo())
                                                .eq("store_no", scope.storeNo())
                                                .eq("order_no", orderNo)
                                                .eq("deleted", 0)));
        if (binding == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return result(binding);
    }

    @Override
    public Result query(Identity identity, String key) {
        outside();
        identity(identity);
        key(key);
        return queryOriginal(identity, key, false);
    }

    @Override
    public Result queryForStore(Identity identity, String key) {
        outside();
        staffIdentity(identity);
        key(key);
        return queryOriginal(identity, key, true);
    }

    private Result queryOriginal(Identity identity, String key, boolean staff) {
        var intent = intent(identity, key);
        if (intent == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var binding = binding(identity, intent.getPayNo(), false);
        if (staff && !"POSTPAY".equals(binding.getOrderMode())) throw unsupported();
        if ("ACCEPTED".equals(binding.getStatus())) return result(binding);
        if ("BOUND".equals(binding.getStatus()))
            return "POSTPAY".equals(binding.getOrderMode())
                    ? acceptUnpaidOriginal(identity, binding.getOrderNo())
                    : result(binding);
        if (Set.of(OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING)
                        .contains(intent.getStatus())
                && intent.getNextAttemptAt() <= System.currentTimeMillis())
            recover(identity, intent.getPayNo());
        return result(binding(identity, intent.getPayNo(), false));
    }

    @Override
    public Result get(Identity identity, String orderNo) {
        outside();
        identity(identity);
        PricingSourceFacts.key(orderNo);
        var bound = binding(identity, orderNo, false);
        return "POSTPAY".equals(bound.getOrderMode()) && "BOUND".equals(bound.getStatus())
                ? acceptUnpaid(identity, orderNo)
                : result(bound);
    }

    private OrdCreateIntent prepare(
            Command command, String key, String hash, Snapshot snapshot, boolean tombstone) {
        try {
            return numbers.transaction(
                    Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 1),
                    () -> {
                        var order = numbers.takeReserved(DocumentType.TRADE_ORDER);
                        var sub = numbers.takeReserved(DocumentType.TRADE_SUBORDER);
                        var identity = command.identity();
                        var quantities = new LinkedHashMap<String, Integer>();
                        if (!tombstone && !requiresDemandPlan(snapshot))
                            snapshot.prices()
                                    .units()
                                    .forEach(
                                            u ->
                                                    quantities.merge(
                                                            u.skuNo(),
                                                            quantity(u.quantity()),
                                                            Math::addExact));
                        var needs =
                                quantities.entrySet().stream()
                                        .map(
                                                e ->
                                                        new StockPort.SkuQty(
                                                                e.getKey(),
                                                                e.getValue(),
                                                                identity.storeNo(),
                                                                sub.value(),
                                                                command.mode() == Mode.POSTPAY
                                                                        ? 3
                                                                        : 2))
                                        .toList();
                        var intent = new OrdCreateIntent();
                        audit(intent, identity);
                        intent.setPayNo(order.value());
                        intent.setUserNo(identity.buyerNo());
                        intent.setIdentityRevision(1L);
                        intent.setEndpoint(endpoint(identity));
                        intent.setIdemKey(key);
                        intent.setRequestHash(hash);
                        intent.setStockAuthority(AUTHORITY);
                        intent.setStockNeedsJson(json.writeValueAsString(needs));
                        intent.setStatus(
                                tombstone ? OrdCreateIntent.RELEASED : OrdCreateIntent.PREPARED);
                        intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
                        intents.insert(intent);
                        var binding = new OrdIndustryOrderSource();
                        audit(binding, identity);
                        binding.setOrderNo(order.value());
                        binding.setSubOrderNo(sub.value());
                        binding.setEntityNo(identity.entityNo());
                        binding.setStoreNo(identity.storeNo());
                        binding.setBuyerNo(identity.buyerNo());
                        binding.setActorNo(identity.actorNo());
                        binding.setPartyNo(identity.partyNo());
                        binding.setSourceSystem(command.source().sourceSystem());
                        binding.setSourceType(command.source().sourceType());
                        binding.setSourceNo(command.source().sourceNo());
                        binding.setSourceVersion(command.source().sourceVersion());
                        binding.setSourceHash(snapshot.snapshotHash());
                        binding.setSourceSubjectNo(snapshot.subjectNo());
                        binding.setQuoteNo(command.quoteNo());
                        binding.setConfirmationDigest(command.confirmationDigest());
                        binding.setRequestHash(hash);
                        binding.setOrderMode(command.mode().name());
                        binding.setStatus(tombstone ? "RELEASED" : "PREPARED");
                        bindings.insert(binding);
                        numbers.bindReserved(
                                new DocumentSource(
                                        identity.tenantNo(), "CORE", "TRADE_ORDER", order.value()),
                                order);
                        numbers.bindReserved(
                                new DocumentSource(
                                        identity.tenantNo(), "CORE", "TRADE_SUBORDER", sub.value()),
                                sub);
                        return intent;
                    });
        } catch (DuplicateKeyException concurrent) {
            var existing = intent(command.identity(), key);
            if (existing == null) throw conflict();
            exact(existing, command.identity(), hash);
            return existing;
        }
    }

    private Result persist(
            Command command,
            String hash,
            String orderNo,
            Prepared prepared,
            PricingCheckoutPort.QuoteVO quote,
            Guard guard,
            String fulfillment) {
        var intent = intents.lock(orderNo);
        exact(intent, command.identity(), hash);
        var binding = binding(command.identity(), orderNo, true);
        if ("BOUND".equals(binding.getStatus())) return result(binding);
        if (!OrdCreateIntent.PREPARED.equals(intent.getStatus())
                || !binding.getSourceHash().equals(prepared.source().snapshotHash())
                || !binding.getRequestHash().equals(hash)) throw conflict();
        var identity = command.identity();
        var order = new OrdOrder();
        audit(order, identity);
        order.setOrderNo(orderNo);
        order.setUserNo(identity.buyerNo());
        order.setIdentityRevision(1L);
        order.setCommunityNo(guard.communityNo());
        order.setGoodsAmount(quote.totals().goodsMinor());
        order.setFreightAmount(0L);
        order.setFeeMinor(quote.totals().feeMinor() == null ? 0L : quote.totals().feeMinor());
        order.setDiscountAmount(quote.totals().discountMinor());
        order.setPayAmount(quote.totals().receivableMinor());
        order.setCurrency(quote.currency());
        order.setStatus(OrdOrder.WAIT_PAY);
        order.setPayScene(PayScenes.normalize(command.payScene()));
        order.setPointsIntegrated(false);
        // A postpaid service debt has no prepayment timeout; accepting it does not acknowledge
        // money.
        order.setPayDeadlineAt(
                command.mode() == Mode.POSTPAY
                        ? null
                        : System.currentTimeMillis() + guard.minutes() * 60_000L);
        payments.insert(order);
        var sub = new OrdSubOrder();
        audit(sub, identity);
        sub.setOrderNo(orderNo);
        sub.setSubOrderNo(binding.getSubOrderNo());
        sub.setUserNo(identity.buyerNo());
        sub.setIdentityRevision(1L);
        sub.setEntityNo(identity.entityNo());
        sub.setStoreNo(identity.storeNo());
        sub.setEntityName(guard.entityName());
        sub.setCommunityNo(guard.communityNo());
        sub.setFulfillment(fulfillment);
        if (guard.delivery() != null) {
            sub.setReceiverName(guard.delivery().receiverName());
            sub.setReceiverPhone(guard.delivery().receiverPhone());
            sub.setReceiverAddress(guard.delivery().address());
            binding.setDeliverySnapshot(
                    json.writeValueAsString(
                            new DeliveryEvidence(guard.delivery(), guard.deliveryOrigin())));
        }
        sub.setInventoryLifecycleVersion(command.mode() == Mode.POSTPAY ? 3 : 2);
        sub.setTrafficSource(guard.trafficSource());
        sub.setGoodsAmount(order.getGoodsAmount());
        sub.setFreightAmount(0L);
        sub.setFeeMinor(order.getFeeMinor());
        sub.setDiscountAmount(order.getDiscountAmount());
        sub.setDiscountPlatform(0L);
        sub.setDiscountMerchant(order.getDiscountAmount());
        sub.setPointsDeduct(0);
        sub.setPointsDeductMinor(0L);
        sub.setPayAmount(order.getPayAmount());
        sub.setStatus(OrdSubOrder.WAIT_PAY);
        sub.setRequireBuyerConfirm(guard.buyerConfirm() ? 1 : 0);
        sub.setBuyerNickname(guard.buyerNickname());
        subOrders.insert(sub);
        var allocations = new ArrayList<Allocation>();
        var evidence = new ArrayList<Object>();
        for (var component : prepared.source().composition()) {
            var unit = component.unit();
            var priced =
                    quote.lines().stream()
                            .filter(
                                    line ->
                                            unit.sourceLineNo().equals(line.sourceLineNo())
                                                    && unit.componentRef()
                                                            .equals(line.componentRef()))
                            .findFirst()
                            .orElse(null);
            boolean included = component.kind() == Kind.BUNDLE_INCLUDED;
            if ((priced == null) != included) throw conflict();
            long receivable = included ? 0 : priced.receivableMinor();
            Object priceEvidence =
                    included ? Map.of("kind", "BUNDLE_INCLUDED", "receivableMinor", 0) : priced;
            var sku = guard.skus().get(unit.skuNo());
            var item = new OrdItem();
            audit(item, identity);
            item.setOrderNo(orderNo);
            item.setSubOrderNo(sub.getSubOrderNo());
            item.setGoodsNo(unit.goodsNo());
            item.setSkuNo(unit.skuNo());
            item.setTitle(sku.title());
            item.setCover(sku.cover());
            item.setSpec(sku.spec());
            item.setPrice(unit.unitPrice().minor());
            item.setQty(billingQuantity(unit.quantity()));
            item.setSaleQuantity(unit.quantity());
            Plan frozenDemand =
                    binding.getDemandPlanSnapshot() == null
                            ? null
                            : json.readValue(binding.getDemandPlanSnapshot(), Plan.class);
            String physicalUnit =
                    frozenDemand == null
                            ? "EA"
                            : frozenDemand.branches().stream()
                                    .filter(b -> unit.componentRef().equals(b.sourceComponentRef()))
                                    .map(b -> b.unit())
                                    .findFirst()
                                    .orElse("EA");
            item.setQuantityUnit(physicalUnit);
            item.setSourceComponentRef(unit.componentRef());
            item.setAmount(unit.baseAmount().minor());
            item.setPaidShareMinor(receivable);
            item.setCategoryType(sku.categoryType());
            item.setCategoryNo(sku.categoryNo());
            item.setSupplyStoreNo(identity.storeNo());
            item.setShippedQty(0);
            item.setIsGift(false);
            item.setWeighed(unit.quantity().stripTrailingZeros().scale() > 0);
            item.setRefundedQty(0);
            item.setRefundedMinor(0L);
            items.insert(item);
            if (item.getId() == null) throw conflict();
            String digest =
                    PricingSnapshotHash.of(
                            List.of(
                                    "INDUSTRY_ITEM_ALLOCATION_V1",
                                    orderNo,
                                    item.getId(),
                                    unit,
                                    priceEvidence));
            allocations.add(
                    new Allocation(
                            unit.sourceLineNo(),
                            unit.componentRef(),
                            item.getId(),
                            item.getQty(),
                            receivable,
                            quote.currency(),
                            digest,
                            unit.quantity(),
                            physicalUnit));
            evidence.add(
                    Map.of(
                            "allocation",
                            allocations.getLast(),
                            "source",
                            unit,
                            "price",
                            priceEvidence));
        }
        // Existing quote service joins this transaction: quote retain + original rows + binding
        // commit atomically.
        var retained =
                quotes.retain(
                        new Context(
                                identity.tenantNo(),
                                identity.entityNo(),
                                identity.storeNo(),
                                prepared.source().subjectNo(),
                                identity.actorNo()),
                        command.quoteNo(),
                        orderNo);
        if (retained == null || !orderNo.equals(retained.retainedRef())) throw conflict();
        var retainedResult = json.readTree(retained.resultSnapshot());
        var feeNode = retainedResult.get("feePlan");
        if (feeNode != null && !feeNode.isNull()) {
            var feePlan = json.treeToValue(feeNode, PricingFeeFacts.Plan.class);
            var providers =
                    feeProviders == null
                            ? List.<PricingFeeRulePort>of()
                            : feeProviders.orderedStream().limit(2).toList();
            if (providers.size() != 1) throw unsupported();
            providers
                    .getFirst()
                    .applyPlan(
                            new Scope(identity.tenantNo(), identity.entityNo(), identity.storeNo()),
                            identity.actorNo(),
                            orderNo,
                            sub.getSubOrderNo(),
                            command.quoteNo(),
                            feePlan);
        } else if (order.getFeeMinor() != 0) throw conflict();
        binding.setEconomicsHash(retained.economicsHash());
        if (guard.party() != null) binding.setPartySnapshot(json.writeValueAsString(guard.party()));
        binding.setAllocationsJson(
                json.writeValueAsString(new BoundAllocations(allocations, evidence)));
        binding.setStatus("BOUND");
        binding.setUpdatedAt(LocalDateTime.now());
        update(binding);
        intent.setStatus(OrdCreateIntent.COMMITTED);
        intent.setUpdatedAt(LocalDateTime.now());
        update(intent);
        var log = new OrdStatusLog();
        log.setTenantNo(identity.tenantNo());
        log.setSubOrderNo(sub.getSubOrderNo());
        log.setAggregateType("ORDER");
        log.setAggregateNo(sub.getSubOrderNo());
        log.setStatus(OrdSubOrder.WAIT_PAY);
        log.setLabel("已下单，待付款");
        log.setOperatorType(OrdStatusLog.BY_USER);
        log.setOperatorNo(identity.actorNo());
        log.setAt(System.currentTimeMillis());
        log.setCreatedAt(LocalDateTime.now());
        statusLogs.insert(log);
        events.publish(
                new OrderEvents.OrderCreated(
                        orderNo,
                        identity.buyerNo(),
                        List.of(sub.getSubOrderNo()),
                        order.getPayAmount()));
        return new Result(
                orderNo,
                sub.getSubOrderNo(),
                command.quoteNo(),
                order.getStatus(),
                order.getCurrency(),
                order.getPayAmount(),
                command.source().sourceVersion(),
                command.mode() == Mode.POSTPAY ? 3 : 2,
                allocations,
                command.mode().name(),
                sub.getStatus(),
                false);
    }

    private Guard guards(
            Identity identity,
            Prepared prepared,
            PricingCheckoutPort.QuoteVO quote,
            Mode mode,
            String fulfillment,
            Party party,
            Delivery delivery) {
        if (!ownerScopes.entityActive(identity.tenantNo(), identity.entityNo())
                || !ownerScopes.storesOwned(
                        identity.tenantNo(), identity.entityNo(), Set.of(identity.storeNo())))
            throw unsupported();
        var card = stores.cards(List.of(identity.storeNo())).get(identity.storeNo());
        if (card == null || !card.active() || !identity.entityNo().equals(card.entityNo()))
            throw unsupported();
        var buyer =
                party == null
                        ? users.findActive(identity.tenantNo(), identity.buyerNo())
                                .orElseThrow(IndustryOrderServiceImpl::unsupported)
                        : null;
        var merchant =
                merchants
                        .find(identity.entityNo())
                        .filter(m -> m.canSell() && (mode == Mode.POSTPAY || m.canReceive()))
                        .orElseThrow(IndustryOrderServiceImpl::unsupported);
        var units = prepared.source().composition().stream().map(c -> c.unit()).toList();
        var snapshots =
                goods.snapshot(
                        units.stream().map(u -> u.skuNo()).distinct().toList(),
                        Map.of(identity.entityNo(), identity.storeNo()));
        var quantities = new HashMap<String, Integer>();
        var limits = new HashMap<String, Integer>();
        for (var unit : units) {
            int qty = billingQuantity(unit.quantity());
            var sku = snapshots.get(unit.skuNo());
            if (sku == null
                    || !unit.goodsNo().equals(sku.goodsNo())
                    || !unit.skuNo().equals(sku.skuNo())
                    || !identity.entityNo().equals(sku.merchantNo())
                    || !identity.storeNo().equals(sku.storeNo())
                    || !sku.onSale()
                    || sku.fulfillments() == null
                    || !sku.fulfillments().contains(fulfillment)
                    || sku.activityOnly()
                    || !Set.of("NORMAL", "FRESH").contains(Objects.toString(sku.categoryType(), ""))
                    || sku.saleMode() != null && !"NORMAL".equals(sku.saleMode()))
                throw unsupported();
            quantities.merge(unit.goodsNo(), qty, Math::addExact);
            if (sku.limitPerUser() != null && sku.limitPerUser() > 0)
                limits.put(unit.goodsNo(), sku.limitPerUser());
        }
        var exactQuantities = new HashMap<String, BigDecimal>();
        for (var unit : units)
            exactQuantities.merge(unit.goodsNo(), unit.quantity(), BigDecimal::add);
        if (party != null && !limits.isEmpty()) throw unsupported();
        purchaseLimits.requireMeasured(identity.buyerNo(), exactQuantities, limits);
        admission.requireOrderAllowed(
                identity.entityNo(), quote.totals().receivableMinor(), () -> paidToday(identity));
        var deliveryOrigin =
                delivery == null
                        ? null
                        : merchants
                                .deliveryOrigin(identity.entityNo(), identity.storeNo())
                                .orElse(null);
        if (deliveryOrigin != null && deliveryOrigin.radiusM() > 0) {
            if (delivery.latE6() == null || delivery.lngE6() == null) throw bad();
            double latitudeMeters =
                    (delivery.latE6() - (double) deliveryOrigin.latE6()) / 1_000_000d * 111_320d;
            double longitudeMeters =
                    (delivery.lngE6() - (double) deliveryOrigin.lngE6())
                            / 1_000_000d
                            * 111_320d
                            * Math.cos(Math.toRadians(deliveryOrigin.latE6() / 1_000_000d));
            if (Math.hypot(latitudeMeters, longitudeMeters) > deliveryOrigin.radiusM())
                throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean buyerConfirm =
                admission.requireFulfillmentAllowed(identity.entityNo(), fulfillment, null);
        if (mode == Mode.PREPAY) {
            var pay = merchants.payCapabilityOf(identity.entityNo(), identity.storeNo());
            if (pay == null || pay.payMethods() == null || pay.payMethods().isEmpty())
                throw unsupported();
            if (pay.wouldExceed(quote.totals().receivableMinor()))
                throw BizException.of(ErrorCode.MERCHANT_QUOTA_EXHAUSTED);
        }
        int minutes = closeRules.unpaidMinutes();
        if (minutes < CloseRuleService.MIN_UNPAID_MINUTES
                || minutes > CloseRuleService.MAX_UNPAID_MINUTES) throw unsupported();
        String traffic =
                party == null
                        ? attribution.resolveTrafficSource(identity.buyerNo(), identity.entityNo())
                        : OrdSubOrder.TRAFFIC_MERCHANT_OWNED;
        if (!Set.of(OrdSubOrder.TRAFFIC_MERCHANT_OWNED, OrdSubOrder.TRAFFIC_PLATFORM)
                .contains(traffic)) throw unsupported();
        if (!"CNY".equals(quote.currency())
                || quote.totals().discountMinor() < 0
                || quote.totals().discountMinor() > quote.totals().goodsMinor()
                || mode == Mode.PREPAY && quote.totals().discountMinor() != 0
                || quote.totals().receivableMinor() < 0
                || quote.lines().size() != prepared.source().prices().units().size())
            throw unsupported();
        return new Guard(
                snapshots,
                merchant.merchantName(),
                party == null ? buyer.nickname() : party.displayName(),
                buyerConfirm,
                traffic,
                party == null ? userDetails.communityOf(identity.buyerNo()).orElse(null) : null,
                minutes,
                party,
                delivery,
                deliveryOrigin);
    }

    private long paidToday(Identity identity) {
        return DataScopeContext.executeWithoutScope(
                        () ->
                                subOrders.selectList(
                                        Wrappers.<OrdSubOrder>lambdaQuery()
                                                .eq(OrdSubOrder::getTenantNo, identity.tenantNo())
                                                .eq(OrdSubOrder::getEntityNo, identity.entityNo())
                                                .ge(
                                                        OrdSubOrder::getCreatedAt,
                                                        LocalDate.now().atStartOfDay())
                                                .notIn(
                                                        OrdSubOrder::getStatus,
                                                        OrdSubOrder.WAIT_PAY,
                                                        OrdSubOrder.CANCELLED,
                                                        OrdSubOrder.REFUNDED)))
                .stream()
                .filter(s -> !Integer.valueOf(3).equals(s.getInventoryLifecycleVersion()))
                .mapToLong(s -> s.getPayAmount() == null ? 0 : s.getPayAmount())
                .reduce(0, Math::addExact);
    }

    private void authorizedSource(Command command, Prepared prepared, boolean staff) {
        var identity = command.identity();
        var source = prepared.source();
        if (!command.source().equals(source.source())
                || !identity.tenantNo().equals(source.scope().tenantNo())
                || !identity.entityNo().equals(source.scope().entityNo())
                || !identity.storeNo().equals(source.scope().storeNo())
                || !staff && source.subjectNo() == null
                || staff
                        && (source.subjectNo() != null
                                || !Set.of("POS", "WAITER").contains(source.channel()))) {
            throw unsupported();
        }
        IndustryOrderCompositionFacts.require(source, command.mode());
    }

    private void recover(Identity identity, String orderNo) {
        boolean claim =
                transaction(
                        () -> {
                            var intent = intents.lock(orderNo);
                            if (intent == null
                                    || !Set.of(OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING)
                                            .contains(intent.getStatus())
                                    || intent.getNextAttemptAt() > System.currentTimeMillis())
                                return false;
                            intent.setStatus(OrdCreateIntent.RECOVERING);
                            intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
                            update(intent);
                            return true;
                        });
        if (!claim) return;
        var source = binding(identity, orderNo, false);
        if (source.getDemandPlanSnapshot() != null) {
            var plan = json.readValue(source.getDemandPlanSnapshot(), Plan.class);
            var targets =
                    plan.branches().stream()
                            .flatMap(b -> b.leaves().stream())
                            .map(l -> new InventoryDemandPlanPort.Target(l.leafRef(), l.quantity()))
                            .toList();
            demandReceipt(
                    plan,
                    demands()
                            .releaseTo(
                                    plan.scope(),
                                    "CANCEL_" + source.getOrderNo(),
                                    plan.planHash(),
                                    targets),
                    Set.of("RELEASED", "EXPIRED"));
        } else {
            var cancelled = inventory().cancelAll(inventoryScope(source));
            if (cancelled == null
                    || !inventoryScope(source).equals(cancelled.scope())
                    || cancelled.state() != InventoryReservationPort.State.RELEASED
                            && cancelled.state() != InventoryReservationPort.State.EXPIRED)
                throw conflict();
        }
        transaction(
                () -> {
                    var intent = intents.lock(orderNo);
                    if (!OrdCreateIntent.RECOVERING.equals(intent.getStatus())) throw conflict();
                    intent.setStatus(OrdCreateIntent.RELEASED);
                    update(intent);
                    var locked = binding(identity, orderNo, true);
                    locked.setStatus("RELEASED");
                    update(locked);
                    return null;
                });
    }

    private Result result(OrdIndustryOrderSource binding) {
        if ("CHANNEL".equals(binding.getOrderMode())) {
            if (channelOrders == null || !"CHANNEL_ORDER".equals(binding.getSourceType()))
                throw conflict();
            return channelOrders.industryResult(
                    new IndustryChannelOrderPort.Scope(
                            binding.getTenantNo(), binding.getEntityNo(), binding.getStoreNo()),
                    binding.getOrderNo());
        }
        int lifecycle = "POSTPAY".equals(binding.getOrderMode()) ? 3 : 2;
        if (!Set.of("BOUND", "ACCEPTED").contains(binding.getStatus()))
            return new Result(
                    binding.getOrderNo(),
                    binding.getSubOrderNo(),
                    binding.getQuoteNo(),
                    binding.getStatus(),
                    null,
                    null,
                    binding.getSourceVersion(),
                    lifecycle,
                    List.of(),
                    binding.getOrderMode(),
                    null,
                    false);
        var order =
                DataScopeContext.executeWithoutScope(
                        () ->
                                payments.selectOne(
                                        Wrappers.<OrdOrder>lambdaQuery()
                                                .eq(OrdOrder::getTenantNo, binding.getTenantNo())
                                                .eq(OrdOrder::getOrderNo, binding.getOrderNo())
                                                .eq(OrdOrder::getUserNo, binding.getBuyerNo())
                                                .eq(OrdOrder::getDeleted, 0)));
        if (order == null) throw conflict();
        var allocations =
                json.readValue(binding.getAllocationsJson(), BoundAllocations.class).allocations();
        var sub =
                DataScopeContext.executeWithoutScope(
                        () ->
                                subOrders.selectOne(
                                        Wrappers.<OrdSubOrder>lambdaQuery()
                                                .eq(OrdSubOrder::getTenantNo, binding.getTenantNo())
                                                .eq(OrdSubOrder::getEntityNo, binding.getEntityNo())
                                                .eq(OrdSubOrder::getStoreNo, binding.getStoreNo())
                                                .eq(OrdSubOrder::getOrderNo, binding.getOrderNo())
                                                .eq(
                                                        OrdSubOrder::getSubOrderNo,
                                                        binding.getSubOrderNo())));
        boolean accepted = lifecycle == 3 && "ACCEPTED".equals(binding.getStatus());
        if (accepted
                && (sub == null
                        || !Integer.valueOf(3).equals(sub.getInventoryLifecycleVersion())
                        || !Set.of(
                                        OrdSubOrder.WAIT_FULFILL,
                                        OrdSubOrder.FULFILLING,
                                        OrdSubOrder.COMPLETED)
                                .contains(sub.getStatus())
                        || !OrdOrder.WAIT_PAY.equals(order.getStatus())
                        || order.getPaidAt() != null)) throw conflict();
        long adjustment =
                amendmentRows == null
                        ? 0
                        : amendmentRows.receivableAdjustment(
                                binding.getTenantNo(), binding.getEntityNo(), binding.getOrderNo());
        var projection =
                amendments == null
                        ? null
                        : amendments.effectiveAllocations(
                                new Reference(
                                        new Scope(
                                                binding.getTenantNo(),
                                                binding.getEntityNo(),
                                                binding.getStoreNo()),
                                        new PricingSourceRef(
                                                binding.getSourceSystem(),
                                                binding.getSourceType(),
                                                binding.getSourceNo(),
                                                binding.getSourceVersion(),
                                                null,
                                                null),
                                        binding.getOrderNo(),
                                        binding.getSubOrderNo()),
                                allocations);
        Long effective = adjustment == 0 ? null : Math.addExact(order.getPayAmount(), adjustment);
        if (effective != null && effective < 0) throw conflict();
        return new Result(
                order.getOrderNo(),
                binding.getSubOrderNo(),
                binding.getQuoteNo(),
                order.getStatus(),
                order.getCurrency(),
                order.getPayAmount(),
                binding.getSourceVersion(),
                lifecycle,
                allocations,
                binding.getOrderMode(),
                sub == null ? null : sub.getStatus(),
                accepted,
                binding.getDemandPlanSnapshot() == null
                        ? null
                        : json.readValue(binding.getDemandPlanSnapshot(), Plan.class),
                effective,
                adjustment == 0 ? null : adjustment,
                projection == null || projection.sourceHash() == null
                        ? null
                        : projection.allocations(),
                projection == null ? null : projection.sourceHash());
    }

    private OrdCreateIntent intent(Identity identity, String key) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        intents.selectOne(
                                new QueryWrapper<OrdCreateIntent>()
                                        .eq("tenant_no", identity.tenantNo())
                                        .eq("endpoint", endpoint(identity))
                                        .eq("idem_key", key)
                                        .eq("user_no", identity.buyerNo())
                                        .eq("stock_authority", AUTHORITY)
                                        .eq("deleted", 0)));
    }

    private OrdIndustryOrderSource binding(Identity identity, String orderNo, boolean lock) {
        var query =
                new QueryWrapper<OrdIndustryOrderSource>()
                        .eq("tenant_no", identity.tenantNo())
                        .eq("entity_no", identity.entityNo())
                        .eq("store_no", identity.storeNo())
                        .eq("buyer_no", identity.buyerNo())
                        .eq("actor_no", identity.actorNo())
                        .eq("order_no", orderNo)
                        .eq(identity.partyNo() != null, "party_no", identity.partyNo())
                        .isNull(identity.partyNo() == null, "party_no")
                        .eq("deleted", 0);
        if (lock) query.last("FOR UPDATE");
        var result = DataScopeContext.executeWithoutScope(() -> bindings.selectOne(query));
        if (result == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return result;
    }

    private List<StockPort.SkuQty> needs(OrdCreateIntent intent) {
        return json.readValue(intent.getStockNeedsJson(), new TypeReference<>() {});
    }

    private InventoryReservationPort inventory() {
        var candidates = inventoryProviders.orderedStream().limit(2).toList();
        if (candidates.size() != 1) throw unsupported();
        return candidates.getFirst();
    }

    private InventoryReservationPort.Scope inventoryScope(OrdIndustryOrderSource binding) {
        return new InventoryReservationPort.Scope(
                binding.getTenantNo(), binding.getEntityNo(), binding.getOrderNo());
    }

    private <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> DataScopeContext.executeWithoutScope(action));
    }

    private void update(OrdCreateIntent intent) {
        if (intents.updateById(intent) != 1) throw conflict();
    }

    private void update(OrdIndustryOrderSource source) {
        if (bindings.updateById(source) != 1) throw conflict();
    }

    private Prepared prepareSource(Command command, boolean staff) {
        return staff
                ? preparations.staff(scope(command.identity()), command.source())
                : preparations.customer(command.source(), command.credentials());
    }

    private Prepared confirmSource(Command command, String hash, boolean staff) {
        return staff
                ? preparations.confirmStaff(scope(command.identity()), command.source(), hash)
                : preparations.confirmCustomer(command.source(), command.credentials(), hash);
    }

    private PricingCheckoutPort.QuoteVO revalidate(Command command, boolean staff) {
        return staff
                ? checkout.revalidateStaff(
                        scope(command.identity()),
                        command.identity().actorNo(),
                        command.source(),
                        command.quoteNo(),
                        command.confirmationDigest())
                : checkout.revalidateCustomer(
                        command.source(),
                        command.credentials(),
                        command.quoteNo(),
                        command.confirmationDigest());
    }

    private static Scope scope(Identity identity) {
        return new Scope(identity.tenantNo(), identity.entityNo(), identity.storeNo());
    }

    private void validateStaff(Command command, String key) {
        if (command == null) throw bad();
        staffIdentity(command.identity());
        key(key);
        if (command.mode() != Mode.POSTPAY
                || command.credentials() != null
                || command.source() == null
                || !"FOOD".equals(command.source().sourceSystem())
                || !"CART".equals(command.source().sourceType())
                || command.source().scopeNo() != null
                || command.source().scopeVersion() != null) throw unsupported();
        PricingSourceFacts.key(command.quoteNo());
        if (command.confirmationDigest() == null
                || !command.confirmationDigest().matches("[0-9a-f]{64}")) throw bad();
    }

    /**
     * Scope and staff actor are resolved by the authenticated B gateway, never by a consumer
     * request.
     */
    private void staffIdentity(Identity value) {
        if (value == null) throw bad();
        try {
            PricingSourceFacts.key(value.tenantNo());
            PricingSourceFacts.key(value.entityNo());
            PricingSourceFacts.key(value.storeNo());
            if (value.partyNo() == null) PricingSourceFacts.key(value.buyerNo());
            else {
                PricingSourceFacts.key(value.partyNo());
                if (!"".equals(value.buyerNo())) throw bad();
            }
            PricingSourceFacts.key(value.actorNo());
        } catch (IllegalArgumentException invalid) {
            throw bad();
        }
        if (!"MAIN".equals(value.tenantNo())) throw unsupported();
    }

    private static void validate(Command command, String key) {
        if (command == null) throw bad();
        identity(command.identity());
        key(key);
        if (command.mode() == null
                || command.credentials() == null
                || command.source() == null
                || !"FOOD".equals(command.source().sourceSystem())
                || !"CART".equals(command.source().sourceType())
                || command.source().scopeNo() != null
                || command.mode() == Mode.PREPAY
                        && (command.credentials().sessionNo() != null
                                || command.credentials().accessToken() != null)
                || command.identity().partyNo() == null
                        && !command.identity().buyerNo().equals(command.credentials().buyerNo())
                || command.identity().partyNo() != null
                        && (command.mode() != Mode.POSTPAY
                                || !command.identity()
                                        .partyNo()
                                        .equals(command.credentials().sessionNo())
                                || command.credentials().accessToken() == null
                                || command.credentials().buyerNo() != null)) throw unsupported();
        PricingSourceFacts.key(command.quoteNo());
        if (command.confirmationDigest() == null
                || !command.confirmationDigest().matches("[0-9a-f]{64}")) throw bad();
    }

    private static void identity(Identity identity) {
        if (identity == null) throw bad();
        try {
            PricingSourceFacts.key(identity.tenantNo());
            PricingSourceFacts.key(identity.entityNo());
            PricingSourceFacts.key(identity.storeNo());
            if (identity.partyNo() == null) PricingSourceFacts.key(identity.buyerNo());
            else {
                PricingSourceFacts.key(identity.partyNo());
                if (!"".equals(identity.buyerNo())) throw bad();
            }
            PricingSourceFacts.key(identity.actorNo());
        } catch (IllegalArgumentException invalid) {
            throw bad();
        }
        if (!"MAIN".equals(identity.tenantNo())
                || !(identity.partyNo() == null ? identity.buyerNo() : identity.partyNo())
                        .equals(identity.actorNo())) throw unsupported();
    }

    private static void exact(OrdCreateIntent intent, Identity identity, String hash) {
        if (intent == null
                || !identity.buyerNo().equals(intent.getUserNo())
                || !hash.equals(intent.getRequestHash())
                || !AUTHORITY.equals(intent.getStockAuthority())) throw conflict();
    }

    private static String endpoint(Identity identity) {
        return "INDUSTRY_ORDER:" + PricingSnapshotHash.of(identity);
    }

    private static Context quoteContext(Identity identity) {
        return new Context(
                identity.tenantNo(),
                identity.entityNo(),
                identity.storeNo(),
                identity.buyerNo(),
                identity.actorNo());
    }

    private static boolean requiresDemandPlan(Snapshot source) {
        return source.composition().stream()
                .anyMatch(
                        p ->
                                p.kind() != Kind.MAIN && p.kind() != Kind.ADDON
                                        || p.unit().quantity().stripTrailingZeros().scale() > 0);
    }

    private Plan frozenPlan(Command command, OrdIndustryOrderSource original, Snapshot source) {
        if (original.getDemandPlanSnapshot() != null)
            return json.readValue(original.getDemandPlanSnapshot(), Plan.class);
        Plan plan = sources.demandPlan(command, original.getOrderNo(), original.getSubOrderNo());
        if (plan == null) {
            if (requiresDemandPlan(source)) throw unsupported();
            return null;
        }
        if (command.mode() != Mode.POSTPAY
                || !InventoryDemandPlanFacts.hash(plan).equals(plan.planHash()))
            throw unsupported();
        var parts =
                source.composition().stream()
                        .collect(
                                Collectors.toMap(
                                        p -> p.unit().componentRef(),
                                        p -> p.unit(),
                                        (a, b) -> {
                                            throw conflict();
                                        }));
        var seen = new HashSet<String>();
        for (var branch : plan.branches()) {
            var part = parts.get(branch.sourceComponentRef());
            if (part == null
                    || !seen.add(branch.sourceComponentRef())
                    || part.quantity().compareTo(branch.quantity()) != 0) throw unsupported();
        }
        for (var part : source.composition()) {
            boolean container =
                    part.kind() == Kind.MAIN
                            && source.composition().stream()
                                    .anyMatch(
                                            c ->
                                                    c.unit()
                                                                    .sourceLineNo()
                                                                    .equals(
                                                                            part.unit()
                                                                                    .sourceLineNo())
                                                            && c.kind()
                                                                    .name()
                                                                    .startsWith("BUNDLE_"));
            if (!container && !seen.contains(part.unit().componentRef())) throw unsupported();
        }
        return transaction(
                () -> {
                    var intent = intents.lock(original.getOrderNo());
                    if (!OrdCreateIntent.PREPARED.equals(intent.getStatus())) throw conflict();
                    var locked = binding(command.identity(), original.getOrderNo(), true);
                    if (locked.getDemandPlanSnapshot() != null) {
                        if (!plan.planHash().equals(locked.getDemandPlanHash())) throw conflict();
                        return json.readValue(locked.getDemandPlanSnapshot(), Plan.class);
                    }
                    locked.setDemandPlanNo(plan.planNo());
                    locked.setDemandPlanHash(plan.planHash());
                    locked.setDemandPlanSnapshot(json.writeValueAsString(plan));
                    update(locked);
                    return plan;
                });
    }

    private InventoryDemandPlanPort demands() {
        var providers =
                demandProviders == null
                        ? List.<InventoryDemandPlanPort>of()
                        : demandProviders.orderedStream().limit(2).toList();
        if (providers.size() != 1) throw unsupported();
        return providers.getFirst();
    }

    private static void demandReceipt(
            Plan plan, InventoryDemandPlanPort.Receipt receipt, Set<String> states) {
        if (receipt == null
                || !plan.scope().equals(receipt.scope())
                || !plan.planNo().equals(receipt.planNo())
                || !plan.planHash().equals(receipt.planHash())
                || !states.contains(receipt.status())) throw conflict();
        var expected =
                plan.branches().stream()
                        .flatMap(b -> b.leaves().stream())
                        .collect(Collectors.toMap(l -> l.leafRef(), l -> l.quantity()));
        if (receipt.lines().size() != expected.size()) throw conflict();
        for (var line : receipt.lines())
            if (!expected.containsKey(line.leafRef())
                    || expected.get(line.leafRef()).compareTo(line.originalQty()) != 0)
                throw conflict();
    }

    private static int billingQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 6)
            throw unsupported();
        return quantity.stripTrailingZeros().scale() > 0 ? 1 : quantity(quantity);
    }

    private static int quantity(BigDecimal quantity) {
        try {
            int value = quantity.intValueExact();
            if (value <= 0) throw unsupported();
            return value;
        } catch (ArithmeticException invalid) {
            throw unsupported();
        }
    }

    private static void audit(BaseEntity row, Identity identity) {
        row.setTenantNo(identity.tenantNo());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        row.setCreatedBy(identity.actorNo());
        row.setUpdatedBy(identity.actorNo());
    }

    private static void key(String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw bad();
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported();
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException unsupported() {
        return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private record Guard(
            Map<String, SkuSnapshot> skus,
            String entityName,
            String buyerNickname,
            boolean buyerConfirm,
            String trafficSource,
            String communityNo,
            int minutes,
            Party party,
            Delivery delivery,
            MerchantQueryPort.DeliveryOrigin deliveryOrigin) {}

    private record DeliveryEvidence(Delivery source, MerchantQueryPort.DeliveryOrigin origin) {}

    private record BoundAllocations(List<Allocation> allocations, List<Object> evidence) {}
}
