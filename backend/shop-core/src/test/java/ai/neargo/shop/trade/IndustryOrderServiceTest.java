package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
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
import ai.neargo.shop.trade.entity.OrdSubOrder;
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
import ai.neargo.shop.spi.product.StockPort;
import tools.jackson.databind.json.JsonMapper;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Test void staffPlacementRejectsCustomerCredentialsAndPrepaymentBeforeAnyIntent() {
        var staff = new Identity("MAIN", "E", "S", "U", "STAFF");
        assertThatThrownBy(() -> service.placeForStore(
                command(staff, new Credentials(null, null, "U"), Mode.POSTPAY), "K"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.placeForStore(command(staff, null, Mode.PREPAY), "K"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(intents, preparations, checkout, inventoryProviders, payments, quotes);
    }

    @Test void staffCancellationRejectsConsumerCredentialsAndUnknownFulfillmentBeforeAnyOwnerCall() {
        var staff = new Identity("MAIN", "E", "S", "U", "STAFF");
        assertThatThrownBy(() -> service.cancelForStore(command(staff, new Credentials(null, null, "U"),
                Mode.POSTPAY), "K", Fulfillments.STORE_PICKUP)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.cancelForStore(command(staff, null, Mode.POSTPAY), "K", "DELIVERY"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(intents, preparations, sources, inventoryProviders, payments, quotes);
    }
    @Test void sessionPlacementRequiresItsRealContractsBeforeAnyIntentOrStockMutation() {
        var session = command(identity, new Credentials("SESSION", "secret", "U"), Mode.PREPAY);
        assertThatThrownBy(() -> service.place(session, "K")).isInstanceOf(BizException.class);
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"100,PREPAY,false,0,STORE_PICKUP", "0,PREPAY,false,0,STORE_PICKUP",
            "100,POSTPAY,false,0,STORE_PICKUP", "0,POSTPAY,false,0,STORE_PICKUP",
            "100,POSTPAY,true,0,STORE_PICKUP", "0,POSTPAY,true,0,STORE_PICKUP",
            "100,POSTPAY,true,20,STORE_PICKUP", "100,POSTPAY,true,20,DINE_IN", "0,POSTPAY,true,0,DINE_IN"})
    void frozenMenuPriceWritesOriginalOrderItemsAndRealTaxSharesThenRetainsQuote(
            long price, Mode mode, boolean staff, long discount, String fulfillment) {
        long goodsAmount = price * 2; long tax = price == 0 ? 0 : 10;
        long net = goodsAmount - discount; long due = net + tax;
        var originalIdentity = staff ? new Identity("MAIN", "E", "S", "U", "STAFF") : identity;
        var command = command(originalIdentity, staff ? null : new Credentials(null, null, "U"), mode);
        var unit = new PricingUnit("LINE", "f".repeat(64), "G", "SKU", "PRICE_V1", "INVOICE", BigDecimal.ZERO,
                new BigDecimal("2"), new MoneyMinor("CNY", price), new MoneyMinor("CNY", goodsAmount));
        var snapshot = PricingSourceFacts.freeze(new PricingSourceFactsPort.Scope("MAIN", "E", "S"),
                staff ? null : "H5_SESSION", source,
                staff ? "POS" : "H5", "b".repeat(64),
                List.of(new PricingSourceFactsPort.Component(PricingSourceFactsPort.Kind.MAIN, unit)));
        var prepared = new Prepared(snapshot, null, null, null, Instant.now().plusSeconds(120), "c".repeat(64));
        var line = new PricingCheckoutPort.LineVO("LINE", "f".repeat(64), "INVOICE", "GOODS", "G", "SKU", BigDecimal.ZERO,
                new BigDecimal("2"), goodsAmount, net, tax, due);
        var quote = new PricingCheckoutPort.QuoteVO("PQ", "READY", Instant.now().plusSeconds(120).toString(), 1,
                "b".repeat(64), "CNY", List.of(line), new PricingCheckoutPort.TotalsVO(goodsAmount, discount, net, tax, due, due),
                "a".repeat(64));
        var staffScope = new PricingSourceFactsPort.Scope("MAIN", "E", "S");
        if (staff) {
            when(preparations.staff(staffScope, source)).thenReturn(prepared);
            when(preparations.confirmStaff(staffScope, source, prepared.preparationHash())).thenReturn(prepared);
            when(checkout.revalidateStaff(staffScope, "STAFF", source, "PQ", command.confirmationDigest()))
                    .thenReturn(quote);
        } else {
            when(preparations.customer(source, command.credentials())).thenReturn(prepared);
            when(preparations.confirmCustomer(source, command.credentials(), prepared.preparationHash()))
                    .thenReturn(prepared);
            when(checkout.revalidateCustomer(source, command.credentials(), "PQ", command.confirmationDigest()))
                    .thenReturn(quote);
        }
        when(ownerScopes.entityActive("MAIN", "E")).thenReturn(true);
        when(ownerScopes.storesOwned("MAIN", "E", Set.of("S"))).thenReturn(true);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(users.findActive("MAIN", "U")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U", "Buyer")));
        when(merchants.find("E")).thenReturn(Optional.of(new MerchantQueryPort.MerchantBrief("E", "Merchant", true,
                true, null, 0, 0, true, 0, false)));
        // Current retail price is deliberately unrelated: only non-price owner admission facts are used.
        var sku = new GoodsQueryPort.SkuSnapshot("SKU", "G", "E", "Dish", null, "regular", "STANDARD", "CATEGORY",
                99999, 50, true, List.of(fulfillment), null, null, "NORMAL", 10, null, "S");
        when(goods.snapshot(List.of("SKU"), Map.of("E", "S"))).thenReturn(Map.of("SKU", sku));
        if(mode==Mode.PREPAY) when(merchants.payCapabilityOf("E", "S")).thenReturn(new MerchantQueryPort.PayCapability(Set.of("WECHAT"), false, 100000, 0));
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
        when(intents.selectOne(any())).thenAnswer(invocation -> intent.get());
        when(bindings.insert(any(OrdIndustryOrderSource.class))).thenAnswer(invocation -> { binding.set(invocation.getArgument(0)); return 1; });
        when(bindings.selectOne(any())).thenAnswer(invocation -> binding.get());
        var inventory = mock(InventoryReservationPort.class);
        when(inventoryProviders.orderedStream()).thenAnswer(invocation -> Stream.of(inventory));
        var stockScope = new InventoryReservationPort.Scope("MAIN", "E", orderNo);
        int lifecycle=mode==Mode.POSTPAY?3:2;
        when(inventory.reserve(eq(stockScope), any(), eq(900L), eq(lifecycle))).thenReturn(new InventoryReservationPort.Snapshot(
                stockScope, "RESERVATION", InventoryReservationPort.State.HELD, lifecycle, false,
                List.of(new InventoryReservationPort.ReservedLine("SKU", 2, subNo))));
        var writtenItem = new AtomicReference<OrdItem>(); var writtenOrder = new AtomicReference<OrdOrder>();
        when(items.insert(any(OrdItem.class))).thenAnswer(invocation -> { var item = (OrdItem) invocation.getArgument(0); item.setId(10L); writtenItem.set(item); return 1; });
        when(payments.insert(any(OrdOrder.class))).thenAnswer(invocation -> { writtenOrder.set(invocation.getArgument(0)); return 1; });
        when(quotes.retain(any(), eq("PQ"), eq(orderNo))).thenReturn(new PricingQuoteCommands.QuoteVO(
                "PQ", "READY", quote.expiresAt(), "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "{}", "{}", "{}", "{}", orderNo));
        var writtenSub=new AtomicReference<OrdSubOrder>();
        when(subOrders.insert(any(OrdSubOrder.class))).thenAnswer(call -> { writtenSub.set(call.getArgument(0)); return 1; });
        if(mode==Mode.POSTPAY) {
            when(inventory.acceptUnpaid(stockScope)).thenReturn(new InventoryReservationPort.Snapshot(stockScope,
                    "RESERVATION",InventoryReservationPort.State.ACCEPTED_UNPAID,3,false,
                    List.of(new InventoryReservationPort.ReservedLine("SKU",2,subNo))));
            when(payments.selectOne(any())).thenAnswer(call -> writtenOrder.get());
            when(subOrders.selectOne(any())).thenAnswer(call -> writtenSub.get());
            when(subOrders.updateById(any(OrdSubOrder.class))).thenReturn(1);
        }
        var result = Fulfillments.DINE_IN.equals(fulfillment) ? service.placeForDiningRoom(command, "KEY")
                : staff ? service.placeForStore(command, "KEY") : service.place(command, "KEY");
        assertThat(result.status()).isEqualTo("WAIT_PAY"); assertThat(result.payAmountMinor()).isEqualTo(due);
        assertThat(writtenSub.get().getFulfillment()).isEqualTo(fulfillment);
        assertThat(intent.get().getRequestHash()).isEqualTo(Fulfillments.DINE_IN.equals(fulfillment)
                ? PricingSnapshotHash.of(List.of("INDUSTRY_DINE_IN_PLACEMENT_V1",command))
                : PricingSnapshotHash.of(command));
        verify(admission).requireFulfillmentAllowed("E",fulfillment,null);
        if (Fulfillments.DINE_IN.equals(fulfillment)) {
            assertThatThrownBy(() -> service.placeForStore(command,"KEY")).isInstanceOfSatisfying(BizException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(service.placeForDiningRoom(command,"KEY").orderNo()).isEqualTo(result.orderNo());
        }
        assertThat(writtenOrder.get().getGoodsAmount()).isEqualTo(goodsAmount);
        assertThat(writtenOrder.get().getDiscountAmount()).isEqualTo(discount);
        assertThat(writtenSub.get().getDiscountAmount()).isEqualTo(discount);
        assertThat(writtenSub.get().getDiscountMerchant()).isEqualTo(discount);
        assertThat(writtenItem.get().getPrice()).isEqualTo(price); assertThat(writtenItem.get().getQty()).isEqualTo(2);
        assertThat(writtenItem.get().getAmount()).isEqualTo(goodsAmount); assertThat(writtenItem.get().getPaidShareMinor()).isEqualTo(due);
        assertThat(result.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.orderItemId()).isEqualTo(10L); assertThat(allocation.sourceLineNo()).isEqualTo("LINE");
            assertThat(allocation.componentRef()).isEqualTo("f".repeat(64));
            assertThat(allocation.amountMinor()).isEqualTo(due); assertThat(allocation.hash()).hasSize(64);
        });
        assertThat(binding.get().getStatus()).isEqualTo(mode==Mode.POSTPAY?"ACCEPTED":"BOUND");
        assertThat(result.orderMode()).isEqualTo(mode.name());
        assertThat(result.inventoryLifecycleVersion()).isEqualTo(lifecycle);
        assertThat(result.acceptedUnpaid()).isEqualTo(mode==Mode.POSTPAY);
        assertThat(writtenOrder.get().getStatus()).isEqualTo("WAIT_PAY");
        assertThat(writtenOrder.get().getPaidAt()).isNull();
        if(mode==Mode.POSTPAY) {
            assertThat(writtenOrder.get().getPayDeadlineAt()).isNull();
            assertThat(writtenSub.get().getStatus()).isEqualTo("WAIT_FULFILL");
            assertThat((staff ? service.queryForStore(originalIdentity, "KEY") : service.query(identity, "KEY"))
                    .orderNo()).isEqualTo(orderNo);
            verify(inventory,times(1)).acceptUnpaid(stockScope);
            verify(inventory,never()).allocatePaid(any());
            verify(inventory,never()).issue(any(),any());
        }
        assertThat(binding.get().getSourceSubjectNo()).isEqualTo(staff ? null : "H5_SESSION");
        assertThat(writtenOrder.get().getUserNo()).isEqualTo("U");
        assertThat(binding.get().getActorNo()).isEqualTo(staff ? "STAFF" : "U");
        assertThat(binding.get().getAllocationsJson()).contains("INVOICE", "taxMinor", "orderItemId");
        assertThat(intent.get().getStatus()).isEqualTo(OrdCreateIntent.COMMITTED);
        verify(quotes).retain(any(), eq("PQ"), eq(orderNo));
        verify(limits).require("U", Map.of("G", 2), Map.of("G", 10));
        verify(events).publish(any());
    }

    @Test void diningRoomRequiresExplicitProductSupportBeforeAnyOriginalIntentOrStockReserve() {
        var staff=new Identity("MAIN","E","S","U","STAFF"); var command=command(staff,null,Mode.POSTPAY);
        var scope=new PricingSourceFactsPort.Scope("MAIN","E","S");
        var unit=new PricingUnit("LINE","f".repeat(64),"G","SKU","PRICE","INVOICE",BigDecimal.ZERO,
                new BigDecimal("2"),new MoneyMinor("CNY",100),new MoneyMinor("CNY",200));
        var snapshot=PricingSourceFacts.freeze(scope,null,source,"POS","b".repeat(64),
                List.of(new PricingSourceFactsPort.Component(PricingSourceFactsPort.Kind.MAIN,unit)));
        var prepared=new Prepared(snapshot,null,null,null,Instant.now().plusSeconds(120),"c".repeat(64));
        when(inventoryProviders.orderedStream()).thenReturn(Stream.of(mock(InventoryReservationPort.class)));
        when(preparations.staff(scope,source)).thenReturn(prepared);
        when(checkout.revalidateStaff(scope,"STAFF",source,"PQ",command.confirmationDigest()))
                .thenReturn(new PricingCheckoutPort.QuoteVO("PQ","READY",Instant.now().plusSeconds(120).toString(),1,
                        "b".repeat(64),"CNY",List.of(new PricingCheckoutPort.LineVO("LINE","f".repeat(64),"INVOICE","GOODS",
                                "G","SKU",BigDecimal.ZERO,new BigDecimal("2"),200,200,0,200)),
                        new PricingCheckoutPort.TotalsVO(200,0,200,0,200,200),"a".repeat(64)));
        when(ownerScopes.entityActive("MAIN","E")).thenReturn(true);
        when(ownerScopes.storesOwned("MAIN","E",Set.of("S"))).thenReturn(true);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S",new StoreDirectoryPort.StoreCard("S","Store","E",null,
                "ACTIVE",true,null,null,null,null,0,0)));
        when(users.findActive("MAIN","U")).thenReturn(Optional.of(new UserScopeFactsPort.ActiveUser("U","Buyer")));
        when(merchants.find("E")).thenReturn(Optional.of(new MerchantQueryPort.MerchantBrief("E","Merchant",true,
                true,null,0,0,true,0,false)));
        when(goods.snapshot(List.of("SKU"),Map.of("E","S"))).thenReturn(Map.of("SKU",new GoodsQueryPort.SkuSnapshot(
                "SKU","G","E","Dish",null,"regular","STANDARD","CATEGORY",99999,50,true,
                List.of(Fulfillments.STORE_PICKUP),null,null,"NORMAL",10,null,"S")));
        assertThatThrownBy(() -> service.placeForDiningRoom(command,"DINE-IN"))
                .isInstanceOfSatisfying(BizException.class,f -> assertThat(f.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        verify(goods).snapshot(List.of("SKU"),Map.of("E","S"));
        verify(intents,never()).insert(any(OrdCreateIntent.class)); verifyNoInteractions(payments,subOrders,admission);
    }

    private record AcceptedFixture(OrdIndustryOrderSource binding,OrdOrder parent,OrdSubOrder child,
            InventoryReservationPort inventory,InventoryReservationPort.Scope scope) { }
    private AcceptedFixture boundPostpaid() {
        var command=command(identity,new Credentials(null,null,"U"),Mode.POSTPAY);
        String hash=PricingSnapshotHash.of(command);
        var bound=new OrdIndustryOrderSource(); bound.setTenantNo("MAIN"); bound.setEntityNo("E");
        bound.setStoreNo("S"); bound.setBuyerNo("U"); bound.setActorNo("U"); bound.setOrderNo("O");
        bound.setSubOrderNo("OS"); bound.setQuoteNo("PQ"); bound.setSourceVersion(1L);
        bound.setOrderMode("POSTPAY"); bound.setStatus("BOUND"); bound.setRequestHash(hash);
        bound.setAllocationsJson(JsonMapper.builder().build().writeValueAsString(Map.of("allocations",List.of(
                new Allocation("LINE","f".repeat(64),10,2,210,"CNY",
                "a".repeat(64))),"evidence",List.of())));
        var intent=new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("U"); intent.setRequestHash(hash);
        intent.setStockAuthority("INDUSTRY_INVENTORY_V2"); intent.setStatus(OrdCreateIntent.COMMITTED);
        intent.setStockNeedsJson(JsonMapper.builder().build().writeValueAsString(
                List.of(new StockPort.SkuQty("SKU",2,"S","OS",3))));
        var parent=new OrdOrder(); parent.setOrderNo("O"); parent.setUserNo("U"); parent.setStatus("WAIT_PAY");
        parent.setCurrency("CNY"); parent.setPayAmount(210L);
        var child=new OrdSubOrder(); child.setOrderNo("O"); child.setSubOrderNo("OS");
        child.setInventoryLifecycleVersion(3); child.setStatus("WAIT_PAY");
        var inventory=mock(InventoryReservationPort.class);
        when(bindings.selectOne(any())).thenReturn(bound); when(intents.selectOne(any())).thenReturn(intent);
        when(inventoryProviders.orderedStream()).thenAnswer(call -> Stream.of(inventory));
        lenient().when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(payments.selectOne(any())).thenReturn(parent);
        lenient().when(subOrders.selectOne(any())).thenReturn(child);
        lenient().when(subOrders.updateById(any(OrdSubOrder.class))).thenReturn(1);
        return new AcceptedFixture(bound,parent,child,inventory,new InventoryReservationPort.Scope("MAIN","E","O"));
    }
    @Test void recoveryOfBoundPostpayUsesOriginalReservationAndAssignsOriginalOwnerPickupCodeWithoutMoney() {
        var fixture=boundPostpaid();
        when(fixture.inventory.acceptUnpaid(fixture.scope)).thenReturn(new InventoryReservationPort.Snapshot(
                fixture.scope,"RES",InventoryReservationPort.State.ACCEPTED_UNPAID,3,false,
                List.of(new InventoryReservationPort.ReservedLine("SKU",2,"OS"))));
        var result=service.query(identity,"ORIGINAL-KEY");
        assertThat(result.acceptedUnpaid()).isTrue(); assertThat(result.status()).isEqualTo("WAIT_PAY");
        assertThat(fixture.parent.getPaidAt()).isNull(); assertThat(fixture.parent.getPayChannel()).isNull();
        assertThat(fixture.child.getVerifyCode()).matches("[0-9]{6}");
        assertThat(service.query(identity,"ORIGINAL-KEY").orderNo()).isEqualTo("O");
        verify(fixture.inventory,times(1)).acceptUnpaid(fixture.scope);
        verify(fixture.inventory,never()).allocatePaid(any()); verify(fixture.inventory,never()).issue(any(),any());
        verifyNoInteractions(preparations,checkout,quotes,numbers);
        assertThatThrownBy(() -> service.cancel(command(identity,new Credentials(null,null,"U"),Mode.POSTPAY),"K"))
                .isInstanceOf(BizException.class);
        verify(fixture.inventory,never()).cancelAll(any());
    }
    @Test void acceptanceRejectsHeldWrongQuantityAndInventedPaymentAcknowledgmentBeforeAnyCoreTransition() {
        var fixture=boundPostpaid();
        for(var receipt:List.of(
                new InventoryReservationPort.Snapshot(fixture.scope,"RES",InventoryReservationPort.State.HELD,3,false,
                        List.of(new InventoryReservationPort.ReservedLine("SKU",2,"OS"))),
                new InventoryReservationPort.Snapshot(fixture.scope,"RES",InventoryReservationPort.State.ACCEPTED_UNPAID,3,true,
                        List.of(new InventoryReservationPort.ReservedLine("SKU",2,"OS"))),
                new InventoryReservationPort.Snapshot(fixture.scope,"RES",InventoryReservationPort.State.ACCEPTED_UNPAID,3,false,
                        List.of(new InventoryReservationPort.ReservedLine("SKU",1,"OS"))))) {
            when(fixture.inventory.acceptUnpaid(fixture.scope)).thenReturn(receipt);
            assertThatThrownBy(() -> service.acceptUnpaid(identity,"O")).isInstanceOf(BizException.class);
            assertThat(fixture.binding.getStatus()).isEqualTo("BOUND");
            assertThat(fixture.child.getStatus()).isEqualTo("WAIT_PAY");
        }
        verifyNoInteractions(payments,subOrders,events,logs);
    }
    @Test void lateUnpaidAcceptancePreservesAnAlreadyCompletedFulfillment() {
        var fixture=boundPostpaid(); fixture.child.setStatus("COMPLETED"); fixture.child.setVerifyCode("123456");
        when(fixture.inventory.acceptUnpaid(fixture.scope)).thenReturn(new InventoryReservationPort.Snapshot(
                fixture.scope,"RES",InventoryReservationPort.State.ISSUED_UNPAID,3,false,
                List.of(new InventoryReservationPort.ReservedLine("SKU",2,"OS"))));
        var result=service.acceptUnpaid(identity,"O");
        assertThat(result.fulfillmentStatus()).isEqualTo("COMPLETED");
        assertThat(fixture.child.getVerifyCode()).isEqualTo("123456");
        assertThat(result.status()).isEqualTo("WAIT_PAY"); verify(subOrders,never()).updateById(any(OrdSubOrder.class));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void uncertainCancellationRetainsOriginalIntentAndResumesSameOwnerReference(int channel) {
        var original = channel == 0 ? identity : new Identity("MAIN", "E", "S", "U", "STAFF");
        var command = command(original, channel == 0 ? new Credentials(null, null, "U") : null,
                channel == 0 ? Mode.PREPAY : Mode.POSTPAY);
        String fulfillment = channel == 2 ? Fulfillments.DINE_IN : Fulfillments.STORE_PICKUP;
        var intent = new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("U");
        intent.setRequestHash(channel == 2
                ? PricingSnapshotHash.of(List.of("INDUSTRY_DINE_IN_PLACEMENT_V1", command))
                : PricingSnapshotHash.of(command)); intent.setStockAuthority("INDUSTRY_INVENTORY_V2");
        intent.setStatus(OrdCreateIntent.PREPARED); intent.setNextAttemptAt(System.currentTimeMillis() + 120_000);
        var binding = new OrdIndustryOrderSource(); binding.setTenantNo("MAIN"); binding.setEntityNo("E");
        binding.setStoreNo("S"); binding.setBuyerNo("U"); binding.setActorNo(original.actorNo()); binding.setOrderNo("O");
        binding.setOrderMode(channel == 0 ? "PREPAY" : "POSTPAY");
        binding.setSubOrderNo("OS"); binding.setQuoteNo("PQ"); binding.setSourceVersion(1L); binding.setStatus("PREPARED");
        when(intents.selectOne(any())).thenReturn(intent); when(intents.lock("O")).thenReturn(intent);
        when(bindings.selectOne(any())).thenReturn(binding);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var inventory = mock(InventoryReservationPort.class);
        when(inventoryProviders.orderedStream()).thenAnswer(invocation -> Stream.of(inventory));
        var scope = new InventoryReservationPort.Scope("MAIN", "E", "O");
        when(inventory.cancelAll(scope)).thenThrow(new IllegalStateException("remote response unknown"))
                .thenReturn(new InventoryReservationPort.Snapshot(scope, "R", InventoryReservationPort.State.RELEASED,
                        channel == 0 ? 2 : 3, false, List.of()));
        assertThatThrownBy(() -> {
            if (channel == 0) service.cancel(command, "K");
            else service.cancelForStore(command, "K", fulfillment);
        }).isInstanceOf(IllegalStateException.class);
        assertThat(intent.getStatus()).isEqualTo(OrdCreateIntent.RECOVERING);
        assertThat(binding.getStatus()).isEqualTo("PREPARED");
        assertThatThrownBy(() -> {
            if (channel == 0) service.place(command, "K");
            else if (channel == 2) service.placeForDiningRoom(command, "K");
            else service.placeForStore(command, "K");
        }).isInstanceOf(BizException.class);
        intent.setNextAttemptAt(0L);
        var recovered = channel == 0 ? service.query(identity, "K") : service.queryForStore(original, "K");
        assertThat(recovered.status()).isEqualTo("RELEASED"); assertThat(recovered.payAmountMinor()).isNull();
        assertThat(intent.getStatus()).isEqualTo(OrdCreateIntent.RELEASED);
        verify(inventory, times(2)).cancelAll(scope);
        verifyNoInteractions(preparations, quotes, payments, items, sources);
    }
    @ParameterizedTest @ValueSource(strings = {"STORE_PICKUP", "DINE_IN"})
    void cancellationBeforeIntentCreatesOriginalTombstoneAndBlocksLatePlacement(String fulfillment) {
        var actor = new Identity("MAIN", "E", "S", "U", "STAFF");
        var command = command(actor, null, Mode.POSTPAY);
        var unit = new PricingUnit("LINE", "f".repeat(64), "G", "SKU", "PRICE_V1", "INVOICE", BigDecimal.ZERO,
                BigDecimal.ONE, new MoneyMinor("CNY", 100), new MoneyMinor("CNY", 100));
        var scope = new PricingSourceFactsPort.Scope("MAIN", "E", "S");
        var snapshot = PricingSourceFacts.freeze(scope, null, source, "POS", "b".repeat(64),
                List.of(new PricingSourceFactsPort.Component(PricingSourceFactsPort.Kind.MAIN, unit)));
        when(sources.cancellationForStore(command, "ORIGINAL", fulfillment)).thenReturn(snapshot);
        var date = LocalDate.of(2026, 10, 24);
        String order = DocumentNumberFormatter.format(DocumentType.TRADE_ORDER, date, 1);
        String child = DocumentNumberFormatter.format(DocumentType.TRADE_SUBORDER, date, 1);
        when(numbers.takeReserved(DocumentType.TRADE_ORDER))
                .thenReturn(new ReservedDocumentNumber(DocumentType.TRADE_ORDER, date, order));
        when(numbers.takeReserved(DocumentType.TRADE_SUBORDER))
                .thenReturn(new ReservedDocumentNumber(DocumentType.TRADE_SUBORDER, date, child));
        when(numbers.transaction(any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        var storedIntent = new AtomicReference<OrdCreateIntent>();
        var storedBinding = new AtomicReference<OrdIndustryOrderSource>();
        when(intents.selectOne(any())).thenAnswer(call -> storedIntent.get());
        when(bindings.selectOne(any())).thenAnswer(call -> storedBinding.get());
        when(intents.insert(any(OrdCreateIntent.class))).thenAnswer(call -> {
            storedIntent.set(call.getArgument(0)); return 1;
        });
        when(bindings.insert(any(OrdIndustryOrderSource.class))).thenAnswer(call -> {
            storedBinding.set(call.getArgument(0)); return 1;
        });
        var cancelled = service.cancelForStore(command, "ORIGINAL", fulfillment);
        assertThat(cancelled.status()).isEqualTo("RELEASED");
        assertThat(cancelled.orderMode()).isEqualTo("POSTPAY");
        assertThat(cancelled.inventoryLifecycleVersion()).isEqualTo(3);
        assertThat(cancelled.allocations()).isEmpty();
        assertThat(storedIntent.get().getStockNeedsJson()).isEqualTo("[]");
        assertThatThrownBy(() -> {
            if (Fulfillments.DINE_IN.equals(fulfillment)) service.placeForDiningRoom(command, "ORIGINAL");
            else service.placeForStore(command, "ORIGINAL");
        }).isInstanceOf(BizException.class);
        assertThat(service.cancelForStore(command, "ORIGINAL", fulfillment)).isEqualTo(cancelled);
        verify(sources).cancellationForStore(command, "ORIGINAL", fulfillment);
        verify(sources, never()).staff(any(), any());
        verifyNoInteractions(inventoryProviders, preparations, checkout, payments, items, quotes);
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
