package ai.neargo.shop.trade.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayChannels;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentInitiationPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.ChannelVO;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PayCommand;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PaymentVO;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.View;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService;
import ai.neargo.shop.trade.service.IndustryCheckoutService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Optional;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** One frozen original payment attempt; uncertainty is queried and never converted into another dispatch or channel. */
@Service
public class IndustryCheckoutServiceImpl implements IndustryCheckoutService {
    private final IndustryOrderSourceMapper bindings;
    private final PaymentMapper payments;
    private final StoreOrderMapper subOrders;
    private final PricingQuoteService quotes;
    private final MerchantQueryPort merchants;
    private final MerchantScopeFactsPort ownerScopes;
    private final StoreDirectoryPort stores;
    private final UserScopeFactsPort users;
    private final PayChannelMasterPort channels;
    private final ObjectProvider<OrdinaryPaymentPort> paymentProviders;
    private final ObjectProvider<OrdinaryPaymentInitiationPort> initiationProviders;
    private final ObjectProvider<InventoryReservationPort> inventoryProviders;
    private final OrderService orders;
    private final PaymentResolutionService resolutions;
    private final PlatformTransactionManager transactionManager;
    private final JsonMapper json = JsonMapper.builder().build();
    @Value("${shop.inventory.stock-authority:PLATFORM}") private String stockAuthority;

    public IndustryCheckoutServiceImpl(
            IndustryOrderSourceMapper bindings, PaymentMapper payments, StoreOrderMapper subOrders,
            PricingQuoteService quotes, MerchantQueryPort merchants, MerchantScopeFactsPort ownerScopes,
            StoreDirectoryPort stores, UserScopeFactsPort users, PayChannelMasterPort channels,
            ObjectProvider<OrdinaryPaymentPort> paymentProviders,
            ObjectProvider<OrdinaryPaymentInitiationPort> initiationProviders,
            ObjectProvider<InventoryReservationPort> inventoryProviders, OrderService orders,
            PaymentResolutionService resolutions,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.bindings = bindings;
        this.payments = payments;
        this.subOrders = subOrders;
        this.quotes = quotes;
        this.merchants = merchants;
        this.ownerScopes = ownerScopes;
        this.stores = stores;
        this.users = users;
        this.channels = channels;
        this.paymentProviders = paymentProviders;
        this.initiationProviders = initiationProviders;
        this.inventoryProviders = inventoryProviders;
        this.orders = orders;
        this.resolutions = resolutions;
        this.transactionManager = transactionManager;
    }

    @Override public View get(Identity identity, String orderNo) {
        outside(); var bound = bound(identity, orderNo);
        return view(bound, false);
    }
    @Override public View pay(PayCommand command) {
        outside(); if (command == null) throw bad();
        var bound = bound(command.identity(), command.orderNo());
        confirmation(bound, command);
        if (bound.binding().getPaymentState() != null) {
            if (!Objects.equals(bound.binding().getPaymentChannel(), command.payChannel())
                    && !(PayChannels.FREE.equals(bound.binding().getPaymentChannel()) && bound.order().getPayAmount() == 0)) {
                throw conflict();
            }
            if (!"OPENING".equals(bound.binding().getPaymentState()) && !"OPEN".equals(bound.binding().getPaymentState())) {
                return queryPayment(command.identity(), command.orderNo());
            }
        } else {
            var request = paymentRequest(bound, command.payChannel());
            transaction(() -> {
                var locked = binding(command.identity(), command.orderNo(), true);
                if (locked.getPaymentState() == null) {
                    locked.setPaymentChannel(request.payChannel()); locked.setPaymentRequestJson(json.writeValueAsString(request));
                    locked.setPaymentState("OPENING"); touch(locked);
                } else if (!request.payChannel().equals(locked.getPaymentChannel())) throw conflict();
                return null;
            });
            bound = bound(command.identity(), command.orderNo());
        }
        var request = frozen(bound);
        if (bound.binding().getPaymentOutTradeNo() == null) {
            // This retries only PAY's idempotent ledger-open, never a gateway dispatch.
            openPayOrder(bound, request);
            var attempt = payment().open(request); matched(bound, attempt);
            String outTradeNo = attempt.outTradeNo();
            String openedState = "SUCCESS".equals(attempt.status()) ? "SUCCESS" : "OPEN";
            transaction(() -> {
                var locked = binding(command.identity(), command.orderNo(), true);
                if (!"OPENING".equals(locked.getPaymentState()) || locked.getPaymentOutTradeNo() != null
                        && !outTradeNo.equals(locked.getPaymentOutTradeNo())) throw conflict();
                locked.setPaymentOutTradeNo(outTradeNo); locked.setPaymentState(openedState); touch(locked); return null;
            });
        }
        boolean dispatch = transaction(() -> {
            var locked = binding(command.identity(), command.orderNo(), true);
            if (!"OPEN".equals(locked.getPaymentState())) return false;
            locked.setPaymentState("DISPATCHING"); touch(locked); return true;
        });
        if (!dispatch) return queryPayment(command.identity(), command.orderNo());
        var original = bound(command.identity(), command.orderNo());
        try {
            var result = initiation().initiate(frozen(original));
            if (result == null || !Objects.equals(original.binding().getPaymentOutTradeNo(), result.outTradeNo())
                    || !Objects.equals(original.binding().getPaymentChannel(), result.payChannel())) throw conflict();
            transaction(() -> {
                var locked = binding(command.identity(), command.orderNo(), true);
                if (!"DISPATCHING".equals(locked.getPaymentState())) throw conflict();
                locked.setPaymentState(result.success() ? "READY" : "UNKNOWN");
                locked.setPayParamsJson(result.success() && result.payParams() != null
                        ? json.writeValueAsString(result.payParams()) : null);
                // Existing gateway contract carries no real parameter expiry: never invent or renew one.
                locked.setParamsExpiresAt(null); touch(locked); return null;
            });
        } catch (RuntimeException uncertain) {
            transaction(() -> {
                var locked = binding(command.identity(), command.orderNo(), true);
                if ("DISPATCHING".equals(locked.getPaymentState())) { locked.setPaymentState("UNKNOWN"); touch(locked); }
                return null;
            });
            return view(bound(command.identity(), command.orderNo()), false);
        }
        return queryPayment(command.identity(), command.orderNo());
    }
    @Override public View queryPayment(Identity identity, String orderNo) {
        outside(); var bound = bound(identity, orderNo);
        String outTradeNo = bound.binding().getPaymentOutTradeNo();
        if (outTradeNo == null || "OPEN".equals(bound.binding().getPaymentState())) return view(bound, false);
        var reference = new OrdinaryPaymentPort.Reference(orderNo, identity.buyerNo(), outTradeNo);
        // Refresh is executed by the actual PAY owner (embedded or remote), including its channel verification.
        var lookup = payment().refresh(reference);
        if (lookup == null || !lookup.found() || lookup.payment() == null) return unknown(bound);
        matched(bound, lookup.payment());
        var attempt = lookup.payment();
        if (!"SUCCESS".equals(attempt.status())) {
            if ("CLOSED".equals(attempt.status())) state(identity, orderNo, "CLOSED");
            // PENDING does not prove UNPAID or NOT_FOUND; retain the original uncertainty and any actual parameters.
            return view(bound(identity, orderNo), false);
        }
        if (!"SUCCESS".equals(attempt.status()) || attempt.tradeNo() == null || attempt.tradeNo().isBlank()) return unknown(bound);
        // Callback-equivalent projection begins outside our metadata transactions and retains original owner checks.
        orders.markPaid(orderNo, attempt.payChannel(), attempt.tradeNo());
        state(identity, orderNo, "SUCCESS");
        return view(bound(identity, orderNo), true);
    }
    @Override public View finalizeCheckout(Identity identity, String orderNo) {
        // Food acceptance is conditional on this exact successful receipt and the inventory owner's actual allocation.
        return queryPayment(identity, orderNo);
    }
    private SettlePort.PaymentOpen paymentRequest(Bound bound, String requested) {
        var identity = bound.identity(); var order = bound.order();
        if (!"INVENTORY".equals(stockAuthority)) throw unsupported();
        if (!OrdOrder.WAIT_PAY.equals(order.getStatus()) || order.getPayDeadlineAt() == null
                || order.getPayDeadlineAt() <= System.currentTimeMillis()
                || resolutions.view(order.getOrderNo(), identity.buyerNo()) != null) throw conflict();
        var prior = payment().success(new OrdinaryPaymentPort.OrderReference(order.getOrderNo(), identity.buyerNo()));
        if (prior == null || prior.found()) throw conflict();
        if (!ownerScopes.entityActive(identity.tenantNo(), identity.entityNo())
                || !ownerScopes.storesOwned(identity.tenantNo(), identity.entityNo(), Set.of(identity.storeNo()))
                || users.findActive(identity.tenantNo(), identity.buyerNo()).isEmpty()) throw unsupported();
        var card = stores.cards(List.of(identity.storeNo())).get(identity.storeNo());
        if (card == null || !card.active() || !identity.entityNo().equals(card.entityNo())) throw unsupported();
        merchants.find(identity.entityNo()).filter(m -> m.canSell() && m.canReceive()).orElseThrow(IndustryCheckoutServiceImpl::unsupported);
        var cap = merchants.payCapabilityOf(identity.entityNo(), identity.storeNo());
        if (cap == null || cap.wouldExceed(order.getPayAmount())) throw unsupported();
        String market = merchants.marketOf(identity.entityNo());
        if (market == null || market.isBlank()) throw unsupported();
        var payable = channels.payableChannels(market);
        if (payable == null) throw unsupported();
        String selected = requested;
        if (order.getPayAmount() == 0) selected = PayChannels.FREE;
        if (selected == null || selected.isBlank()) throw bad();
        PaymentRoute route;
        if (PayChannels.FREE.equals(selected)) {
            if (order.getPayAmount() != 0) throw unsupported();
            route = payable.stream().filter(ch -> Objects.equals(order.getCurrency(), channels.currencyOf(ch)))
                    .map(ch -> merchants.paymentRouteOf(identity.entityNo(), identity.storeNo(), ch))
                    .flatMap(Optional::stream).findFirst().orElseThrow(IndustryCheckoutServiceImpl::unsupported);
        } else {
            if (!payable.contains(selected) || !Objects.equals(order.getCurrency(), channels.currencyOf(selected))) throw unsupported();
            route = merchants.paymentRouteOf(identity.entityNo(), identity.storeNo(), selected)
                    .orElseThrow(IndustryCheckoutServiceImpl::unsupported);
        }
        if (!identity.entityNo().equals(route.merchantNo()) || !identity.storeNo().equals(route.storeNo())
                || route.payMerchantNo() == null || route.payMerchantNo().isBlank()
                || route.subMerchantId() == null || route.subMerchantId().isBlank()
                || route.accountVersion() == null || route.accountVersion().isBlank()
                || route.fundsPath() == null || !Set.of("DIRECT", "AGGREGATED").contains(route.fundsPath())
                || !PayChannels.FREE.equals(selected) && !selected.equals(route.channel())) throw unsupported();
        return new SettlePort.PaymentOpen(order.getOrderNo(), identity.buyerNo(), identity.entityNo(), selected,
                order.getPayAmount(), "订单 " + order.getOrderNo(), order.getCurrency(),
                List.of(new SettlePort.PaymentStore(bound.sub().getSubOrderNo(), route)));
    }
    /** 餐饮结账也先建支付单（TDD-支付服务-I3支付单退款流水 S3）：门店是这张账单所属的门店 */
    private void openPayOrder(Bound bound, SettlePort.PaymentOpen request) {
        PaymentPort port = paymentPorts == null ? null : paymentPorts.getIfAvailable();
        if (port == null || request.stores().isEmpty()) return;
        merchants.payStoreOf(request.stores().get(0).route().storeNo()).ifPresent(s -> port.createPayment(
                new PaymentPort.InternalCreatePaymentCmd(PaymentPort.FOOD_CHECK, request.orderNo(), request.orderNo(),
                        s.storeId(), s.storeName(), s.entityName(), s.market(), request.amountMinor(),
                        request.currency(), request.subject(), System.currentTimeMillis() + 15 * 60_000L,
                        s.fundsPath())));
    }

    @Autowired(required = false) private ObjectProvider<PaymentPort> paymentPorts;

    private Bound bound(Identity identity, String orderNo) {
        identity(identity); key(orderNo);
        var source = binding(identity, orderNo, false);
        if (!"BOUND".equals(source.getStatus()) || !"PREPAY".equals(source.getOrderMode())
                || source.getEconomicsHash() == null || source.getSourceSubjectNo() == null) throw conflict();
        var order = DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getTenantNo, identity.tenantNo()).eq(OrdOrder::getOrderNo, orderNo)
                .eq(OrdOrder::getUserNo, identity.buyerNo()).eq(OrdOrder::getDeleted, 0)));
        var sub = DataScopeContext.executeWithoutScope(() -> subOrders.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getTenantNo, identity.tenantNo()).eq(OrdSubOrder::getSubOrderNo, source.getSubOrderNo())
                .eq(OrdSubOrder::getOrderNo, orderNo).eq(OrdSubOrder::getEntityNo, identity.entityNo())
                .eq(OrdSubOrder::getStoreNo, identity.storeNo()).eq(OrdSubOrder::getUserNo, identity.buyerNo())
                .eq(OrdSubOrder::getDeleted, 0)));
        if (order == null || sub == null || order.getPayAmount() == null || order.getPayAmount() < 0
                || !Objects.equals(order.getPayAmount(), sub.getPayAmount()) || !Integer.valueOf(2).equals(sub.getInventoryLifecycleVersion())
                || order.getCurrency() == null || !order.getCurrency().matches("[A-Z]{3}")) throw conflict();
        var quote = quotes.get(new PricingQuoteCommands.Context(identity.tenantNo(), identity.entityNo(), identity.storeNo(),
                source.getSourceSubjectNo(), identity.actorNo()), source.getQuoteNo());
        if (quote == null || !orderNo.equals(quote.retainedRef()) || !source.getEconomicsHash().equals(quote.economicsHash())) throw conflict();
        return new Bound(identity, source, order, sub);
    }
    private void confirmation(Bound bound, PayCommand command) {
        var source = bound.binding(); var ref = command.source();
        if (ref == null || !Objects.equals(source.getSourceSystem(), ref.sourceSystem())
                || !Objects.equals(source.getSourceType(), ref.sourceType()) || !Objects.equals(source.getSourceNo(), ref.sourceNo())
                || source.getSourceVersion() != ref.sourceVersion() || ref.scopeNo() != null
                || !Objects.equals(source.getQuoteNo(), command.quoteNo())
                || !Objects.equals(source.getConfirmationDigest(), command.confirmationDigest())) throw conflict();
    }
    private void matched(Bound bound, OrdinaryPaymentPort.Attempt attempt) {
        var source = bound.binding(); var request = frozen(bound);
        if (attempt == null || !bound.order().getOrderNo().equals(attempt.orderNo())
                || !bound.identity().buyerNo().equals(attempt.userNo()) || attempt.outTradeNo() == null || attempt.outTradeNo().isBlank()
                || source.getPaymentOutTradeNo() != null && !source.getPaymentOutTradeNo().equals(attempt.outTradeNo())
                || !Objects.equals(request.entityNo(), attempt.entityNo()) || !request.payChannel().equals(attempt.payChannel())
                || attempt.amountMinor() == null || request.amountMinor() != attempt.amountMinor()
                || !request.currency().equals(attempt.currency())) throw conflict();
    }
    private View view(Bound bound, boolean receiptSuccess) {
        var source = bound.binding(); var order = bound.order();
        String status = Objects.requireNonNullElse(source.getPaymentState(), "NOT_STARTED");
        if (Set.of("OPENING", "DISPATCHING", "OPEN").contains(status)) status = "UNKNOWN";
        Map<String, Object> params = Map.of();
        if (source.getPayParamsJson() != null && Set.of("READY", "UNPAID").contains(status)
                && order.getPayDeadlineAt() != null && order.getPayDeadlineAt() > System.currentTimeMillis()) {
            params = json.readValue(source.getPayParamsJson(), new TypeReference<>() { });
        }
        boolean ready = receiptSuccess && OrdOrder.PAID.equals(order.getStatus()) && OrdSubOrder.PAID.contains(bound.sub().getStatus())
                && allocated(bound);
        return new View(order.getOrderNo(), source.getSubOrderNo(), source.getQuoteNo(), source.getSourceVersion(),
                source.getSourceHash(), source.getEconomicsHash(), source.getConfirmationDigest(), order.getStatus(),
                order.getCurrency(), order.getPayAmount(), order.getPayDeadlineAt(), channelViews(bound),
                new PaymentVO(source.getPaymentOutTradeNo(), source.getPaymentChannel(), status, params,
                        source.getParamsExpiresAt() == null ? null : Instant.ofEpochMilli(source.getParamsExpiresAt()).toString()), ready);
    }
    private List<ChannelVO> channelViews(Bound bound) {
        if (!OrdOrder.WAIT_PAY.equals(bound.order().getStatus())) return List.of();
        var result = new ArrayList<ChannelVO>();
        String market = merchants.marketOf(bound.identity().entityNo());
        var enabled = market == null ? null : channels.enabledChannels(market);
        var payable = market == null ? null : channels.payableChannels(market);
        if (enabled == null || payable == null) return List.of();
        for (var channel : enabled) {
            boolean available = payable.contains(channel) && Objects.equals(bound.order().getCurrency(), channels.currencyOf(channel))
                    && merchants.paymentRouteOf(bound.identity().entityNo(), bound.identity().storeNo(), channel).isPresent()
                    && (bound.binding().getPaymentChannel() == null || channel.equals(bound.binding().getPaymentChannel()));
            result.add(new ChannelVO(channel, available, available ? null : "CHANNEL_OR_ORIGINAL_ATTEMPT_UNAVAILABLE"));
        }
        return result;
    }
    private boolean allocated(Bound bound) {
        try {
            var candidates = inventoryProviders.orderedStream().limit(2).toList();
            if (candidates.size() != 1) return false;
            var scope = new InventoryReservationPort.Scope(bound.identity().tenantNo(), bound.identity().entityNo(), bound.order().getOrderNo());
            var actual = candidates.getFirst().query(scope);
            if (actual == null || !scope.equals(actual.scope()) || actual.lifecycleVersion() != 2 || !actual.paymentAcknowledged()
                    || !Set.of(InventoryReservationPort.State.ALLOCATED, InventoryReservationPort.State.PARTIALLY_COMMITTED,
                            InventoryReservationPort.State.COMMITTED).contains(actual.state())) return false;
            var evidence = json.readTree(bound.binding().getAllocationsJson()).path("allocations");
            var expected = new HashMap<String, Integer>();
            for (var line : json.readTree(bound.binding().getAllocationsJson()).path("evidence")) {
                expected.merge(line.path("source").path("skuNo").asText(), line.path("allocation").path("qty").asInt(), Math::addExact);
            }
            var received = new HashMap<String, Integer>();
            for (var line : actual.originalLines()) {
                if (!bound.sub().getSubOrderNo().equals(line.fulfillmentRef())) return false;
                received.merge(line.skuNo(), line.qty(), Math::addExact);
            }
            return !evidence.isEmpty() && !expected.isEmpty() && expected.equals(received);
        } catch (RuntimeException unavailable) { return false; }
    }
    private View unknown(Bound bound) { state(bound.identity(), bound.order().getOrderNo(), "UNKNOWN"); return view(bound(bound.identity(), bound.order().getOrderNo()), false); }
    private void state(Identity identity, String no, String state) {
        transaction(() -> {
            var source = binding(identity, no, true);
            // A concurrent channel query cannot erase an in-flight dispatch or its returned original parameters.
            if (!"DISPATCHING".equals(source.getPaymentState()) || "SUCCESS".equals(state)) {
                source.setPaymentState(state); touch(source);
            }
            return null;
        });
    }
    private SettlePort.PaymentOpen frozen(Bound bound) {
        if (bound.binding().getPaymentRequestJson() == null) throw conflict();
        var request = json.readValue(bound.binding().getPaymentRequestJson(), SettlePort.PaymentOpen.class);
        if (!bound.order().getOrderNo().equals(request.orderNo()) || !bound.identity().buyerNo().equals(request.userNo())
                || !bound.identity().entityNo().equals(request.entityNo()) || request.amountMinor() != bound.order().getPayAmount()
                || !bound.order().getCurrency().equals(request.currency())
                || !Objects.equals(bound.binding().getPaymentChannel(), request.payChannel()) || request.stores().size() != 1
                || !bound.sub().getSubOrderNo().equals(request.stores().getFirst().subOrderNo())) throw conflict();
        return request;
    }
    private OrdIndustryOrderSource binding(Identity identity, String no, boolean lock) {
        var query = new QueryWrapper<OrdIndustryOrderSource>().eq("tenant_no", identity.tenantNo()).eq("entity_no", identity.entityNo())
                .eq("store_no", identity.storeNo()).eq("buyer_no", identity.buyerNo()).eq("actor_no", identity.actorNo())
                .eq("order_no", no).eq("deleted", 0);
        if (lock) query.last("FOR UPDATE");
        var value = DataScopeContext.executeWithoutScope(() -> bindings.selectOne(query));
        if (value == null) throw BizException.of(ErrorCode.NOT_FOUND); return value;
    }
    private void touch(OrdIndustryOrderSource source) {
        source.setUpdatedAt(LocalDateTime.now());
        if (bindings.updateById(source) != 1) throw conflict();
    }
    private <T> T transaction(Supplier<T> action) { return new TransactionTemplate(transactionManager).execute(tx -> DataScopeContext.executeWithoutScope(action)); }
    private OrdinaryPaymentPort payment() { var values = paymentProviders.orderedStream().limit(2).toList(); if (values.size() != 1) throw unsupported(); return values.getFirst(); }
    private OrdinaryPaymentInitiationPort initiation() { var values = initiationProviders.orderedStream().limit(2).toList(); if (values.size() != 1) throw unsupported(); return values.getFirst(); }
    private static void identity(Identity identity) {
        if (identity == null) throw bad();
        try { PricingSourceFacts.key(identity.tenantNo()); PricingSourceFacts.key(identity.entityNo()); PricingSourceFacts.key(identity.storeNo());
            PricingSourceFacts.key(identity.buyerNo()); PricingSourceFacts.key(identity.actorNo()); }
        catch (IllegalArgumentException invalid) { throw bad(); }
        if (!"MAIN".equals(identity.tenantNo()) || !identity.buyerNo().equals(identity.actorNo())) throw unsupported();
    }
    private static void key(String no) { try { PricingSourceFacts.key(no); } catch (IllegalArgumentException invalid) { throw bad(); } }
    private static void outside() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported(); }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private record Bound(Identity identity, OrdIndustryOrderSource binding, OrdOrder order, OrdSubOrder sub) { }
}
