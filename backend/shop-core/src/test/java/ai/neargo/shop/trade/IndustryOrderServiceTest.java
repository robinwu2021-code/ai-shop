package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.numbering.DocumentNumberFormatter;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.ReservedDocumentNumber;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.AdmissionPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.CloseRuleService;
import ai.neargo.shop.trade.service.PricingCheckoutService;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import ai.neargo.shop.trade.service.PricingSourceService;
import ai.neargo.shop.trade.service.impl.IndustryOrderServiceImpl;
import ai.neargo.shop.trade.service.impl.PurchaseLimitGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class IndustryOrderServiceTest {
    private final Identity identity = new Identity("MAIN", "E", "S", "U", "U");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 1, null, null);
    @Mock CreateIntentMapper intents;
    @Mock IndustryOrderSourceMapper bindings;
    @Mock PaymentMapper payments;
    @Mock StoreOrderMapper subOrders;
    @Mock OrderItemMapper items;
    @Mock OrderStatusLogMapper logs;
    @Mock PricingPreparationService preparations;
    @Mock PricingCheckoutService checkout;
    @Mock PricingQuoteService quotes;
    @Mock PricingSourceService sources;
    @Mock OutboxEventBus events;
    @Mock ObjectProvider<InventoryReservationPort> inventoryProviders;
    @Mock DocumentNumberCoordinator numbers;
    @Mock GoodsQueryPort goods;
    @Mock MerchantScopeFactsPort ownerScopes;
    @Mock StoreDirectoryPort stores;
    @Mock MerchantQueryPort merchants;
    @Mock UserScopeFactsPort users;
    @Mock UserQueryPort userDetails;
    @Mock AdmissionPort admission;
    @Mock AttributionPort attribution;
    @Mock PurchaseLimitGuard limits;
    @Mock CloseRuleService closeRules;
    @Mock PlatformTransactionManager manager;
    @InjectMocks IndustryOrderServiceImpl service;
    @BeforeEach void authority() { ReflectionTestUtils.setField(service, "stockAuthority", "INVENTORY");
        lenient().when(intents.updateById(any(OrdCreateIntent.class))).thenReturn(1);
        lenient().when(bindings.updateById(any(OrdIndustryOrderSource.class))).thenReturn(1);
    }
    private Command command(Identity actor, Credentials credentials, Mode mode) {
        return new Command(actor, source, credentials, "PQ", "a".repeat(64), mode, "H5");
    }
    @Test void staffCannotTurnBuyerIdentityIntoPlacementOrCancellationConsent() {
        var staff = new Identity("MAIN", "E", "S", "U", "STAFF");
        var command = command(staff, new Credentials(null, null, "U"), Mode.PREPAY);
        assertThatThrownBy(() -> service.place(command, "K")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.cancel(command, "K")).isInstanceOf(BizException.class);
        verifyNoInteractions(intents, preparations, sources, inventoryProviders, payments, quotes);
    }
    @Test void sessionAndPostpaidRequireTheirRealContractsBeforeAnyIntentOrStockMutation() {
        var session = command(identity, new Credentials("SESSION", "secret", "U"), Mode.PREPAY);
        assertThatThrownBy(() -> service.place(session, "K")).isInstanceOf(BizException.class);
        var postpaid = command(identity, new Credentials(null, null, "U"), Mode.POSTPAY);
        assertThatThrownBy(() -> service.place(postpaid, "K")).isInstanceOf(BizException.class);
        verifyNoInteractions(intents, preparations, sources, inventoryProviders, payments, quotes);
    }
    @Test void unprovenStockAuthorityCannotPublishAnOriginalOrder() {
        ReflectionTestUtils.setField(service, "stockAuthority", "PLATFORM");
        assertThatThrownBy(() -> service.place(command(identity, new Credentials(null, null, "U"), Mode.PREPAY), "K"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        verifyNoInteractions(preparations, inventoryProviders, payments, items, quotes);
    }
    @Test void releasedIntentCannotBeReopenedEvenWhenSourceAndQuoteOwnersAreAvailableAgain() {
        var command = command(identity, new Credentials(null, null, "U"), Mode.PREPAY);
        var intent = new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("U");
        intent.setRequestHash(PricingSnapshotHash.of(command));
        intent.setStockAuthority("INDUSTRY_INVENTORY_V2"); intent.setStatus(OrdCreateIntent.RELEASED);
        var binding = new OrdIndustryOrderSource(); binding.setOrderNo("O"); binding.setStatus("RELEASED");
        when(intents.selectOne(any())).thenReturn(intent); when(bindings.selectOne(any())).thenReturn(binding);
        assertThatThrownBy(() -> service.place(command, "K")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(preparations, inventoryProviders, payments, quotes);
    }
    @Test void sameKeyWithDifferentQuoteNeverLoadsCurrentOwnersOrStock() {
        var command = command(identity, new Credentials(null, null, "U"), Mode.PREPAY);
        var intent = new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("U");
        intent.setRequestHash("b".repeat(64)); intent.setStockAuthority("INDUSTRY_INVENTORY_V2");
        intent.setStatus(OrdCreateIntent.PREPARED); when(intents.selectOne(any())).thenReturn(intent);
        assertThatThrownBy(() -> service.place(command, "K")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(bindings, preparations, inventoryProviders, payments, quotes);
    }
    @Test void unresolvedIntentExposesOriginalDocumentsWithoutInventedMoneyOrCommittedAllocations() {
        var intent = new OrdCreateIntent(); intent.setPayNo("O"); intent.setStatus(OrdCreateIntent.PREPARED);
        intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
        var binding = new OrdIndustryOrderSource(); binding.setOrderNo("O"); binding.setSubOrderNo("OS");
        binding.setQuoteNo("PQ"); binding.setSourceVersion(1L); binding.setStatus("PREPARED");
        when(intents.selectOne(any())).thenReturn(intent); when(bindings.selectOne(any())).thenReturn(binding);
        var result = service.query(identity, "K");
        assertThat(result.orderNo()).isEqualTo("O"); assertThat(result.subOrderNo()).isEqualTo("OS");
        assertThat(result.payAmountMinor()).isNull(); assertThat(result.currency()).isNull();
        assertThat(result.allocations()).isEmpty(); verifyNoInteractions(inventoryProviders, preparations, payments);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {100, 0})
    void frozenMenuPriceWritesOriginalOrderItemsAndRealTaxSharesThenRetainsQuote(long price) {
        long goodsAmount = price * 2; long tax = price == 0 ? 0 : 10; long due = goodsAmount + tax;
        var command = command(identity, new Credentials(null, null, "U"), Mode.PREPAY);
        var unit = new PricingUnit("LINE", "f".repeat(64), "G", "SKU", "PRICE_V1", "INVOICE", BigDecimal.ZERO,
                new BigDecimal("2"), new MoneyMinor("CNY", price), new MoneyMinor("CNY", goodsAmount));
        var snapshot = PricingSourceFacts.freeze(new PricingSourceFactsPort.Scope("MAIN", "E", "S"), "H5_SESSION", source,
                "H5", "b".repeat(64), List.of(new PricingSourceFactsPort.Component(PricingSourceFactsPort.Kind.MAIN, unit)));
        var prepared = new Prepared(snapshot, null, null, null, Instant.now().plusSeconds(120), "c".repeat(64));
        var line = new PricingCheckoutPort.LineVO("LINE", "f".repeat(64), "INVOICE", "GOODS", "G", "SKU", BigDecimal.ZERO,
                new BigDecimal("2"), goodsAmount, goodsAmount, tax, due);
        var quote = new PricingCheckoutPort.QuoteVO("PQ", "READY", Instant.now().plusSeconds(120).toString(), 1,
                "b".repeat(64), "CNY", List.of(line), new PricingCheckoutPort.TotalsVO(goodsAmount, 0, goodsAmount, tax, due, due),
                "a".repeat(64));
        when(preparations.customer(source, command.credentials())).thenReturn(prepared);
        when(preparations.confirmCustomer(source, command.credentials(), prepared.preparationHash())).thenReturn(prepared);
        when(checkout.revalidateCustomer(source, command.credentials(), "PQ", command.confirmationDigest())).thenReturn(quote);
        when(ownerScopes.entityActive("MAIN", "E")).thenReturn(true);
        when(ownerScopes.storesOwned("MAIN", "E", Set.of("S"))).thenReturn(true);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(users.findActive("MAIN", "U")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U", "Buyer")));
        when(merchants.find("E")).thenReturn(Optional.of(new MerchantQueryPort.MerchantBrief("E", "Merchant", true,
                true, null, 0, 0, true, 0, false)));
        // Current retail price is deliberately unrelated: only non-price owner admission facts are used.
        var sku = new GoodsQueryPort.SkuSnapshot("SKU", "G", "E", "Dish", null, "regular", "STANDARD", "CATEGORY",
                99999, 50, true, List.of(Fulfillments.STORE_PICKUP), null, null, "NORMAL", 10, null, "S");
        when(goods.snapshot(List.of("SKU"), Map.of("E", "S"))).thenReturn(Map.of("SKU", sku));
        when(merchants.payCapabilityOf("E", "S")).thenReturn(new MerchantQueryPort.PayCapability(Set.of("WECHAT"), false, 100000, 0));
        when(closeRules.unpaidMinutes()).thenReturn(15);
        when(attribution.resolveTrafficSource("U", "E")).thenReturn(AttributionPort.MERCHANT_OWNED);
        when(userDetails.communityOf("U")).thenReturn(Optional.of("COMM"));
        var date = LocalDate.of(2026, 10, 23);
        String orderNo = DocumentNumberFormatter.format(DocumentType.TRADE_ORDER, date, 1);
        String subNo = DocumentNumberFormatter.format(DocumentType.TRADE_SUBORDER, date, 1);
        when(numbers.takeReserved(DocumentType.TRADE_ORDER)).thenReturn(new ReservedDocumentNumber(DocumentType.TRADE_ORDER, date, orderNo));
        when(numbers.takeReserved(DocumentType.TRADE_SUBORDER)).thenReturn(new ReservedDocumentNumber(DocumentType.TRADE_SUBORDER, date, subNo));
        when(numbers.transaction(any(), any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var intent = new AtomicReference<OrdCreateIntent>(); var binding = new AtomicReference<OrdIndustryOrderSource>();
        when(intents.insert(any(OrdCreateIntent.class))).thenAnswer(invocation -> { intent.set(invocation.getArgument(0)); return 1; });
        when(intents.lock(orderNo)).thenAnswer(invocation -> intent.get());
        when(bindings.insert(any(OrdIndustryOrderSource.class))).thenAnswer(invocation -> { binding.set(invocation.getArgument(0)); return 1; });
        when(bindings.selectOne(any())).thenAnswer(invocation -> binding.get());
        var inventory = mock(InventoryReservationPort.class);
        when(inventoryProviders.orderedStream()).thenAnswer(invocation -> Stream.of(inventory));
        var stockScope = new InventoryReservationPort.Scope("MAIN", "E", orderNo);
        when(inventory.reserve(eq(stockScope), any(), eq(900L), eq(2))).thenReturn(new InventoryReservationPort.Snapshot(
                stockScope, "RESERVATION", InventoryReservationPort.State.HELD, 2, false,
                List.of(new InventoryReservationPort.ReservedLine("SKU", 2, subNo))));
        var writtenItem = new AtomicReference<OrdItem>(); var writtenOrder = new AtomicReference<OrdOrder>();
        when(items.insert(any(OrdItem.class))).thenAnswer(invocation -> { var item = (OrdItem) invocation.getArgument(0); item.setId(10L); writtenItem.set(item); return 1; });
        when(payments.insert(any(OrdOrder.class))).thenAnswer(invocation -> { writtenOrder.set(invocation.getArgument(0)); return 1; });
        when(quotes.retain(any(), eq("PQ"), eq(orderNo))).thenReturn(new PricingQuoteCommands.QuoteVO(
                "PQ", "READY", quote.expiresAt(), "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "{}", "{}", "{}", "{}", orderNo));
        var result = service.place(command, "KEY");
        assertThat(result.status()).isEqualTo("WAIT_PAY"); assertThat(result.payAmountMinor()).isEqualTo(due);
        assertThat(writtenOrder.get().getGoodsAmount()).isEqualTo(goodsAmount);
        assertThat(writtenItem.get().getPrice()).isEqualTo(price); assertThat(writtenItem.get().getQty()).isEqualTo(2);
        assertThat(writtenItem.get().getAmount()).isEqualTo(goodsAmount); assertThat(writtenItem.get().getPaidShareMinor()).isEqualTo(due);
        assertThat(result.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.orderItemId()).isEqualTo(10L); assertThat(allocation.sourceLineNo()).isEqualTo("LINE");
            assertThat(allocation.componentRef()).isEqualTo("f".repeat(64));
            assertThat(allocation.amountMinor()).isEqualTo(due); assertThat(allocation.hash()).hasSize(64);
        });
        assertThat(binding.get().getStatus()).isEqualTo("BOUND");
        assertThat(binding.get().getSourceSubjectNo()).isEqualTo("H5_SESSION");
        assertThat(binding.get().getAllocationsJson()).contains("INVOICE", "taxMinor", "orderItemId");
        assertThat(intent.get().getStatus()).isEqualTo(OrdCreateIntent.COMMITTED);
        verify(quotes).retain(any(), eq("PQ"), eq(orderNo));
        verify(limits).require("U", Map.of("G", 2), Map.of("G", 10));
        verify(events).publish(any());
    }

    @Test void uncertainCancellationRetainsOriginalIntentAndResumesSameOwnerReference() {
        var command = command(identity, new Credentials(null, null, "U"), Mode.PREPAY);
        var intent = new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("U");
        intent.setRequestHash(PricingSnapshotHash.of(command)); intent.setStockAuthority("INDUSTRY_INVENTORY_V2");
        intent.setStatus(OrdCreateIntent.PREPARED); intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
        var binding = new OrdIndustryOrderSource(); binding.setTenantNo("MAIN"); binding.setEntityNo("E");
        binding.setStoreNo("S"); binding.setBuyerNo("U"); binding.setActorNo("U"); binding.setOrderNo("O");
        binding.setSubOrderNo("OS"); binding.setQuoteNo("PQ"); binding.setSourceVersion(1L); binding.setStatus("PREPARED");
        when(intents.selectOne(any())).thenReturn(intent); when(intents.lock("O")).thenReturn(intent);
        when(bindings.selectOne(any())).thenReturn(binding);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var inventory = mock(InventoryReservationPort.class);
        when(inventoryProviders.orderedStream()).thenAnswer(invocation -> Stream.of(inventory));
        var scope = new InventoryReservationPort.Scope("MAIN", "E", "O");
        when(inventory.cancelAll(scope)).thenThrow(new IllegalStateException("remote response unknown"))
                .thenReturn(new InventoryReservationPort.Snapshot(scope, "R", InventoryReservationPort.State.RELEASED,
                        2, false, List.of()));
        assertThatThrownBy(() -> service.cancel(command, "K")).isInstanceOf(IllegalStateException.class);
        assertThat(intent.getStatus()).isEqualTo(OrdCreateIntent.RECOVERING);
        assertThat(binding.getStatus()).isEqualTo("PREPARED");
        assertThatThrownBy(() -> service.place(command, "K")).isInstanceOf(BizException.class);
        intent.setNextAttemptAt(0L);
        var recovered = service.query(identity, "K");
        assertThat(recovered.status()).isEqualTo("RELEASED"); assertThat(recovered.payAmountMinor()).isNull();
        assertThat(intent.getStatus()).isEqualTo(OrdCreateIntent.RELEASED);
        verify(inventory, times(2)).cancelAll(scope);
        verifyNoInteractions(preparations, quotes, payments, items, sources);
    }
    @Test void ownerAbsentReadCannotMasqueradeAsDurableCancellationProof() {
        var command = command(identity, new Credentials(null, null, "U"), Mode.PREPAY);
        var intent = new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("U");
        intent.setRequestHash(PricingSnapshotHash.of(command)); intent.setStockAuthority("INDUSTRY_INVENTORY_V2");
        intent.setStatus(OrdCreateIntent.PREPARED); intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
        var binding = new OrdIndustryOrderSource(); binding.setTenantNo("MAIN"); binding.setEntityNo("E");
        binding.setOrderNo("O"); binding.setStatus("PREPARED");
        when(intents.selectOne(any())).thenReturn(intent); when(intents.lock("O")).thenReturn(intent);
        when(bindings.selectOne(any())).thenReturn(binding);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var inventory = mock(InventoryReservationPort.class);
        when(inventoryProviders.orderedStream()).thenAnswer(invocation -> Stream.of(inventory));
        var scope = new InventoryReservationPort.Scope("MAIN", "E", "O");
        when(inventory.cancelAll(scope)).thenReturn(new InventoryReservationPort.Snapshot(scope, null,
                InventoryReservationPort.State.ABSENT, 0, false, List.of()));
        assertThatThrownBy(() -> service.cancel(command, "K")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(intent.getStatus()).isEqualTo(OrdCreateIntent.RECOVERING);
        assertThat(binding.getStatus()).isEqualTo("PREPARED");
    }
}
