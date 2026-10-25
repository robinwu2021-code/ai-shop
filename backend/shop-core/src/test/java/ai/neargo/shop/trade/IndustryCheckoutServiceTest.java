package ai.neargo.shop.trade;

import static org.mockito.Mockito.inOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;

import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentInitiationPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PayCommand;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.MerchantQueryPort;
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
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import ai.neargo.shop.trade.service.impl.IndustryCheckoutServiceImpl;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Set;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class IndustryCheckoutServiceTest {
    private final Identity identity = new Identity("MAIN", "E", "S", "U", "U");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 1, null, null);
    private final JsonMapper json = JsonMapper.builder().build();
    @Mock IndustryOrderSourceMapper bindings;
    @Mock PaymentMapper payments;
    @Mock StoreOrderMapper subs;
    @Mock PricingQuoteService quotes;
    @Mock MerchantQueryPort merchants;
    @Mock MerchantScopeFactsPort ownerScopes;
    @Mock StoreDirectoryPort stores;
    @Mock UserScopeFactsPort users;
    @Mock PayChannelMasterPort channels;
    @Mock ObjectProvider<OrdinaryPaymentPort> paymentProviders;
    @Mock ObjectProvider<OrdinaryPaymentInitiationPort> initiationProviders;
    @Mock ObjectProvider<InventoryReservationPort> inventoryProviders;
    @Mock SettlePort settlement;
    @Mock OrderService orders;
    @Mock PaymentResolutionService resolutions;
    @Mock PlatformTransactionManager manager;
    IndustryCheckoutServiceImpl service;
    @BeforeEach void authority() {
        // Constructor injection in Mockito erases the three ObjectProvider generic parameters. Bind each
        // authority explicitly so inventory/initiation stubs cannot replace the payment owner fixture.
        service = new IndustryCheckoutServiceImpl(bindings, payments, subs, quotes, merchants, ownerScopes,
                stores, users, channels, paymentProviders, initiationProviders, inventoryProviders, orders,
                resolutions, manager);
        ReflectionTestUtils.setField(service, "stockAuthority", "INVENTORY");
        lenient().when(bindings.updateById(any(OrdIndustryOrderSource.class))).thenReturn(1);
    }
    private record Fixture(OrdIndustryOrderSource binding, OrdOrder order, OrdSubOrder sub, SettlePort.PaymentOpen request) { }
    private PayCommand command(String channel) { return new PayCommand(identity, "O", "PQ", source, "d".repeat(64), channel); }
    private Fixture ready(String state) {
        var binding = new OrdIndustryOrderSource(); binding.setTenantNo("MAIN"); binding.setOrderNo("O");
        binding.setSubOrderNo("OS"); binding.setEntityNo("E"); binding.setStoreNo("S"); binding.setBuyerNo("U"); binding.setActorNo("U");
        binding.setSourceSubjectNo("H5_SESSION");
        binding.setStatus("BOUND"); binding.setOrderMode("PREPAY"); binding.setQuoteNo("PQ"); binding.setSourceVersion(1L);
        binding.setSourceSystem("FOOD"); binding.setSourceType("CART"); binding.setSourceNo("C");
        binding.setSourceHash("a".repeat(64)); binding.setEconomicsHash("b".repeat(64)); binding.setConfirmationDigest("d".repeat(64));
        binding.setAllocationsJson("{\"allocations\":[{\"orderItemId\":10}],\"evidence\":[{\"source\":{\"skuNo\":\"SKU\"},\"allocation\":{\"qty\":2}}]}");
        var order = new OrdOrder(); order.setTenantNo("MAIN"); order.setOrderNo("O"); order.setUserNo("U");
        order.setCurrency("CNY"); order.setPayAmount(210L); order.setStatus(OrdOrder.WAIT_PAY); order.setPayDeadlineAt(System.currentTimeMillis() + 900_000);
        var sub = new OrdSubOrder(); sub.setSubOrderNo("OS"); sub.setPayAmount(210L); sub.setInventoryLifecycleVersion(2);
        sub.setStatus(OrdSubOrder.WAIT_PAY);
        var route = new MerchantQueryPort.PaymentRoute("E", "S", "PM", "WECHAT", "SUB_MCH", "ACCOUNT:2", "DIRECT");
        var request = new SettlePort.PaymentOpen("O", "U", "E", "WECHAT", 210, "订单 O", "CNY", List.of(new SettlePort.PaymentStore("OS", route)));
        if (state != null) {
            binding.setPaymentState(state); binding.setPaymentChannel("WECHAT"); binding.setPaymentOutTradeNo("OUT");
            binding.setPaymentRequestJson(json.writeValueAsString(request));
        }
        when(bindings.selectOne(any())).thenReturn(binding); when(payments.selectOne(any())).thenReturn(order);
        when(subs.selectOne(any())).thenReturn(sub);
        when(quotes.get(any(), eq("PQ"))).thenReturn(new PricingQuoteCommands.QuoteVO("PQ", "EXPIRED", "2026-10-23T01:00:00Z",
                "f".repeat(64), "e".repeat(64), "b".repeat(64), "{}", "{}", "{}", "{}", "O"));
        return new Fixture(binding, order, sub, request);
    }
    private OrdinaryPaymentPort ledger() {
        var ledger = mock(OrdinaryPaymentPort.class);
        when(paymentProviders.orderedStream()).thenAnswer(invocation -> Stream.of(ledger)); return ledger;
    }
    private OrdinaryPaymentPort.Attempt attempt(String state, long amount, String currency) {
        return new OrdinaryPaymentPort.Attempt("P", "OUT", "O", "U", "E", "WECHAT", "TRADE", amount, currency, state, "DOC");
    }
    @Test void staffIdentityNeverBecomesBuyerPaymentConsent() {
        var staff = new Identity("MAIN", "E", "S", "U", "STAFF");
        assertThatThrownBy(() -> service.get(staff, "O")).isInstanceOf(BizException.class);
        verifyNoInteractions(bindings, payments, paymentProviders, initiationProviders);
    }
    @Test void closedCartDoesNotNeedToBeReadAgainForFrozenRetainedOrderEconomics() {
        var fixture = ready(null);
        var view = service.get(identity, "O");
        assertThat(view.amountMinor()).isEqualTo(210); assertThat(view.economicsHash()).isEqualTo("b".repeat(64));
        assertThat(view.payment().status()).isEqualTo("NOT_STARTED"); assertThat(view.readyToAccept()).isFalse();
        verifyNoInteractions(paymentProviders, initiationProviders, inventoryProviders);
    }
    @Test void unknownOriginalAttemptCannotSwitchChannelOrDispatchAgain() {
        ready("UNKNOWN");
        assertThatThrownBy(() -> service.pay(command("ALIPAY"))).isInstanceOf(BizException.class);
        verifyNoInteractions(paymentProviders, initiationProviders);
    }
    @Test void pendingOwnerReceiptRemainsUnknownAndNeverProjectsPaid() {
        var fixture = ready("UNKNOWN"); var ledger = ledger();
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt("PENDING", 210, "CNY")));
        var result = service.queryPayment(identity, "O");
        assertThat(result.payment().status()).isEqualTo("UNKNOWN"); assertThat(result.readyToAccept()).isFalse();
        verifyNoInteractions(orders, settlement, initiationProviders, inventoryProviders);
    }
    @Test void mismatchedRealPaymentAmountCannotProjectOrAcceptOrder() {
        ready("UNKNOWN"); var ledger = ledger();
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt("SUCCESS", 211, "CNY")));
        assertThatThrownBy(() -> service.finalizeCheckout(identity, "O")).isInstanceOf(BizException.class);
        verifyNoInteractions(orders, settlement, inventoryProviders);
    }
    @Test void successfulCashReceiptStillWaitsForActualInventoryPaymentAllocation() {
        var fixture = ready("UNKNOWN"); var ledger = ledger();
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt("SUCCESS", 210, "CNY")));
        doAnswer(invocation -> { fixture.order().setStatus(OrdOrder.PAID); fixture.sub().setStatus(OrdSubOrder.WAIT_FULFILL); return null; })
                .when(orders).markPaid("O", "WECHAT", "TRADE");
        var inventory = mock(InventoryReservationPort.class);
        when(inventoryProviders.orderedStream()).thenAnswer(invocation -> Stream.of(inventory));
        var scope = new InventoryReservationPort.Scope("MAIN", "E", "O");
        when(inventory.query(scope)).thenReturn(new InventoryReservationPort.Snapshot(scope, "R", InventoryReservationPort.State.HELD,
                2, false, List.of(new InventoryReservationPort.ReservedLine("SKU", 2, "OS"))));
        var pending = service.finalizeCheckout(identity, "O");
        assertThat(pending.orderStatus()).isEqualTo("PAID"); assertThat(pending.readyToAccept()).isFalse();
        when(inventory.query(scope)).thenReturn(new InventoryReservationPort.Snapshot(scope, "R", InventoryReservationPort.State.ALLOCATED,
                2, true, List.of(new InventoryReservationPort.ReservedLine("SKU", 2, "OS"))));
        assertThat(service.finalizeCheckout(identity, "O").readyToAccept()).isTrue();
    }
    @Test void gatewayUncertaintyKeepsKnownOriginalAttemptWithoutInventingRetryParameters() {
        var fixture = ready("OPEN"); var ledger = ledger();
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var initiation = mock(OrdinaryPaymentInitiationPort.class);
        when(initiationProviders.orderedStream()).thenAnswer(invocation -> Stream.of(initiation));
        when(initiation.initiate(fixture.request())).thenThrow(new IllegalStateException("timeout after dispatch"));
        var result = service.pay(command("WECHAT"));
        assertThat(result.payment().outTradeNo()).isEqualTo("OUT"); assertThat(result.payment().status()).isEqualTo("UNKNOWN");
        assertThat(result.payment().payParams()).isEmpty(); assertThat(result.payment().paramsExpiresAt()).isNull();
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt("PENDING", 210, "CNY")));
        service.pay(command("WECHAT")); verify(initiation).initiate(fixture.request());
    }

    @Test void zeroFrozenAmountUsesOriginalCurrencyAndActualFreeOwnerReceiptWithoutGatewayDispatch() {
        var fixture = ready(null); fixture.order().setPayAmount(0L); fixture.sub().setPayAmount(0L);
        var ledger = ledger();
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(ledger.success(any())).thenReturn(new OrdinaryPaymentPort.Lookup(false, null));
        when(ownerScopes.entityActive("MAIN", "E")).thenReturn(true);
        when(ownerScopes.storesOwned("MAIN", "E", Set.of("S"))).thenReturn(true);
        when(users.findActive("MAIN", "U")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U", "Buyer")));
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(merchants.find("E")).thenReturn(Optional.of(new MerchantQueryPort.MerchantBrief("E", "Merchant", true,
                true, null, 0, 0, true, 0, false)));
        when(merchants.payCapabilityOf("E", "S")).thenReturn(new MerchantQueryPort.PayCapability(Set.of("JSAPI"), false, 100000, 0));
        when(merchants.marketOf("E")).thenReturn("CN");
        when(channels.payableChannels("CN")).thenReturn(List.of("WECHAT"));
        when(channels.currencyOf("WECHAT")).thenReturn("CNY");
        when(merchants.paymentRouteOf("E", "S", "WECHAT")).thenReturn(Optional.of(fixture.request().stores().getFirst().route()));
        var free = new OrdinaryPaymentPort.Attempt("P", "OUT", "O", "U", "E", "FREE", "FREE_RECEIPT", 0L, "CNY", "SUCCESS", "DOC");
        when(ledger.open(any())).thenReturn(free);
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, free));
        org.mockito.Mockito.doAnswer(call -> { fixture.order().setStatus(OrdOrder.PAID); return null; })
                .when(orders).markPaid("O", "FREE", "FREE_RECEIPT");
        var result = service.pay(command("WECHAT"));
        var opened = ArgumentCaptor.forClass(SettlePort.PaymentOpen.class); verify(ledger).open(opened.capture());
        assertThat(opened.getValue().amountMinor()).isZero(); assertThat(opened.getValue().currency()).isEqualTo("CNY");
        assertThat(opened.getValue().payChannel()).isEqualTo("FREE");
        assertThat(result.orderStatus()).isEqualTo("PAID"); assertThat(result.payment().status()).isEqualTo("SUCCESS");
        verifyNoInteractions(initiationProviders);
    }

    @Test void firstPaymentFreezesActualStoreRouteCurrencyAndReturnedArgumentsBeforeClientUse() {
        var fixture = ready(null); var ledger = ledger();
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(ledger.success(any())).thenReturn(new OrdinaryPaymentPort.Lookup(false, null));
        when(ownerScopes.entityActive("MAIN", "E")).thenReturn(true);
        when(ownerScopes.storesOwned("MAIN", "E", Set.of("S"))).thenReturn(true);
        when(users.findActive("MAIN", "U")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U", "Buyer")));
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(merchants.find("E")).thenReturn(Optional.of(new MerchantQueryPort.MerchantBrief("E", "Merchant", true,
                true, null, 0, 0, true, 0, false)));
        when(merchants.payCapabilityOf("E", "S")).thenReturn(new MerchantQueryPort.PayCapability(Set.of("JSAPI"), false, 100000, 0));
        when(merchants.marketOf("E")).thenReturn("CN");
        when(channels.payableChannels("CN")).thenReturn(List.of("WECHAT"));
        when(channels.enabledChannels("CN")).thenReturn(List.of("WECHAT"));
        when(channels.currencyOf("WECHAT")).thenReturn("CNY");
        when(merchants.paymentRouteOf("E", "S", "WECHAT")).thenReturn(Optional.of(fixture.request().stores().getFirst().route()));
        when(ledger.open(any())).thenReturn(attempt("PENDING", 210, "CNY"));
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt("PENDING", 210, "CNY")));
        var initiation = mock(OrdinaryPaymentInitiationPort.class);
        when(initiationProviders.orderedStream()).thenAnswer(invocation -> Stream.of(initiation));
        when(initiation.initiate(any())).thenReturn(new SettlePort.PayInitResult(true, "OUT", "WECHAT",
                Map.of("package", "prepay_id=actual-owner-token", "nonceStr", "actual-owner-nonce"), null));
        var result = service.pay(command("WECHAT"));
        var opened = ArgumentCaptor.forClass(SettlePort.PaymentOpen.class); verify(ledger).open(opened.capture());
        assertThat(opened.getValue().currency()).isEqualTo("CNY"); assertThat(opened.getValue().amountMinor()).isEqualTo(210);
        assertThat(opened.getValue().stores()).singleElement().satisfies(store -> {
            assertThat(store.subOrderNo()).isEqualTo("OS"); assertThat(store.route().storeNo()).isEqualTo("S");
            assertThat(store.route().payMerchantNo()).isEqualTo("PM"); assertThat(store.route().accountVersion()).isEqualTo("ACCOUNT:2");
        });
        assertThat(result.payment().status()).isEqualTo("READY");
        assertThat(result.payment().payParams()).containsEntry("nonceStr", "actual-owner-nonce");
        assertThat(fixture.binding().getPayParamsJson()).contains("actual-owner-token");
        assertThat(result.payment().paramsExpiresAt()).isNull(); assertThat(result.readyToAccept()).isFalse();
        assertThat(json.writeValueAsString(result)).doesNotContain("SUB_MCH", "ACCOUNT:2", "payMerchantNo", "paymentRequestJson");
    }
    /** 支付服务 I3 S3：餐饮结账先建支付单（FOOD_CHECK、这张账单的门店 id），再开通道尝试 */
    @Test void firstPaymentCreatesFoodPayOrderBeforeOpeningTheAttempt() {
        var fixture = ready(null); var ledger = ledger();
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(ledger.success(any())).thenReturn(new OrdinaryPaymentPort.Lookup(false, null));
        when(ownerScopes.entityActive("MAIN", "E")).thenReturn(true);
        when(ownerScopes.storesOwned("MAIN", "E", Set.of("S"))).thenReturn(true);
        when(users.findActive("MAIN", "U")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U", "Buyer")));
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(merchants.find("E")).thenReturn(Optional.of(new MerchantQueryPort.MerchantBrief("E", "Merchant", true,
                true, null, 0, 0, true, 0, false)));
        when(merchants.payCapabilityOf("E", "S")).thenReturn(new MerchantQueryPort.PayCapability(Set.of("JSAPI"), false, 100000, 0));
        when(merchants.marketOf("E")).thenReturn("CN");
        when(channels.payableChannels("CN")).thenReturn(List.of("WECHAT"));
        when(channels.enabledChannels("CN")).thenReturn(List.of("WECHAT"));
        when(channels.currencyOf("WECHAT")).thenReturn("CNY");
        when(merchants.paymentRouteOf("E", "S", "WECHAT")).thenReturn(Optional.of(fixture.request().stores().getFirst().route()));
        when(ledger.open(any())).thenReturn(attempt("PENDING", 210, "CNY"));
        when(ledger.refresh(any())).thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt("PENDING", 210, "CNY")));
        var initiation = mock(OrdinaryPaymentInitiationPort.class);
        when(initiationProviders.orderedStream()).thenAnswer(invocation -> Stream.of(initiation));
        when(initiation.initiate(any())).thenReturn(new SettlePort.PayInitResult(true, "OUT", "WECHAT",
                Map.of("package", "prepay_id=actual-owner-token", "nonceStr", "actual-owner-nonce"), null));
        var port = mock(PaymentPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<PaymentPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(port);
        ReflectionTestUtils.setField(service, "paymentPorts", ports);
        when(merchants.payStoreOf("S")).thenReturn(Optional.of(new MerchantQueryPort.PayStore(42L, "S", "Store", "E",
                "Merchant", "CN")));
        service.pay(command("WECHAT"));
        var cmd = ArgumentCaptor.forClass(PaymentPort.InternalCreatePaymentCmd.class);
        var order = inOrder(port, ledger);
        order.verify(port).createPayment(cmd.capture());
        order.verify(ledger).open(any());
        assertThat(cmd.getValue().bizType()).isEqualTo(PaymentPort.FOOD_CHECK);
        assertThat(cmd.getValue().storeId()).isEqualTo(42L);
        assertThat(cmd.getValue().outOrderNo()).isEqualTo("O");
        assertThat(cmd.getValue().amountMinor()).isEqualTo(210L);
        assertThat(cmd.getValue().currency()).isEqualTo("CNY");
    }

}
