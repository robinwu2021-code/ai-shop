package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Progress;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Receipt;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Channel;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Component;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.InvoiceLine;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Party;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Totals;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdChannelPolicy;
import ai.neargo.shop.trade.entity.OrdChannelQuote;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelAdmissionGuardMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelFundingHistoryMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelImportMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelPolicyMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelPreparationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelQuoteMapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Real primary migration/transactions with an isolated stock-owner double; this is not an inventory
 * integration test.
 */
class IndustryChannelStockLifecycleTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Scope scope = new Scope("MAIN", "E", "S");
    final String definition = "a".repeat(64);
    final BigDecimal quantity = new BigDecimal("1.25");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final InventoryDemandPlanPort inventory = mock(InventoryDemandPlanPort.class);
    final IndustryChannelOrderSourcePort source = mock(IndustryChannelOrderSourcePort.class);
    IndustryChannelOrderStore store;
    ChannelFundingHistoryMapper histories;
    IndustryChannelStockPreparation service;
    Reference ref;
    Plan originalPlan;

    @BeforeEach
    void setup() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:channel_stock_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(
                        new FileSystemResource(
                                "../shop-app/src/main/resources/db/migration/V607__industry_channel_order_admission.sql"),
                        new FileSystemResource(
                                "../shop-app/src/main/resources/db/migration/V608__industry_channel_admission_guard.sql"),
                        new FileSystemResource(
                                "../shop-app/src/main/resources/db/migration/V613__channel_collection_admission_history.sql"))
                .execute(dataSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChannelImportMapper.class);
        configuration.addMapper(ChannelQuoteMapper.class);
        configuration.addMapper(ChannelAdmissionGuardMapper.class);
        configuration.addMapper(ChannelFundingHistoryMapper.class);
        configuration.addMapper(ChannelPolicyMapper.class);
        configuration.addMapper(ChannelPreparationMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var interceptors = new MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptors);
        var session = new SqlSessionTemplate(factory.getObject());
        store =
                new IndustryChannelOrderStore(
                        session.getMapper(ChannelImportMapper.class),
                        session.getMapper(ChannelAdmissionGuardMapper.class),
                        session.getMapper(ChannelQuoteMapper.class),
                        session.getMapper(ChannelPolicyMapper.class),
                        session.getMapper(ChannelPreparationMapper.class),
                        json,
                        mock(DocumentNumberCoordinator.class),
                        new DataSourceTransactionManager(dataSource),
                        Clock.fixed(now, ZoneOffset.UTC));
        histories = session.getMapper(ChannelFundingHistoryMapper.class);
        store.fundingHistory(histories);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("inventory", inventory);
        service =
                new IndustryChannelStockPreparation(
                        store, beans.getBeanProvider(InventoryDemandPlanPort.class));
        var component =
                new Component(
                        "LINE",
                        null,
                        "MAIN",
                        "SOURCE_LINE",
                        "C",
                        "MAIN",
                        "GOODS",
                        "SKU",
                        "Dish",
                        null,
                        null,
                        quantity,
                        "KG",
                        100,
                        0,
                        0,
                        100,
                        "ORIGINAL_LINE_ALLOCATION");
        var data =
                new Data(
                        scope,
                        "FOOD",
                        "SOURCE",
                        1,
                        "IMPORT",
                        new Channel("MOCK", "PROFILE", definition, "ACCOUNT", "STORE", "AE"),
                        "EXTERNAL",
                        "b".repeat(64),
                        "PLATFORM_READ",
                        "c".repeat(64),
                        "AED",
                        Collection.PLATFORM_COLLECTED,
                        "PLATFORM_PAYMENT",
                        Fulfillment.CHANNEL_DELIVERY,
                        new Party("PARTY", "CUSTOMER", "Name"),
                        null,
                        now.minusSeconds(60),
                        List.of(new InvoiceLine("LINE", quantity, "KG", 100, 0, 0, 100)),
                        List.of(component),
                        new Totals(100, 0, 0, 0, 100, 0, 0, 0, 100));
        ref = new Reference(scope, "FOOD", "SOURCE", 1, PricingSnapshotHash.of(data), "IMPORT");
        store.atomic(
                () -> {
                    var row = new OrdChannelImport();
                    store.audit(row, scope);
                    row.setAdmissionNo("ADMISSION");
                    row.setEntityNo("E");
                    row.setStoreNo("S");
                    row.setChannelStoreKey(definition);
                    row.setExternalOrderKey(PricingSnapshotHash.of("EXTERNAL"));
                    row.setExternalOrderRef("EXTERNAL");
                    row.setSourceSystem("FOOD");
                    row.setSourceNo("SOURCE");
                    row.setSourceVersion(1L);
                    row.setSourceHash(ref.sourceHash());
                    row.setImportRef("IMPORT");
                    row.setQuoteNo("QUOTE");
                    row.setOrderNo("ORDER");
                    row.setSubOrderNo("SUB");
                    row.setStatus("WAITING_STOCK");
                    row.setFence(0L);
                    row.setPolicyNo("POLICY");
                    row.setFundingStatus("RESERVED_PLATFORM");
                    row.setReservedMinor(100L);
                    var collection =
                            new CollectionFacts(
                                    ref,
                                    "EXTERNAL",
                                    data.externalDocumentHash(),
                                    data.externalFinancialHash(),
                                    Collection.PLATFORM_COLLECTED,
                                    100L,
                                    "AED",
                                    "PLATFORM_PAYMENT",
                                    now,
                                    now.plusSeconds(25),
                                    "SOURCE_READ");
                    row.setPolicySnapshot(
                            json.writeValueAsString(
                                    new IndustryChannelOrderStore.FundingGrant(
                                            "POLICY", 0, "AED", 100, 900, collection)));
                    store.imports.insert(row);
                    var quote = new OrdChannelQuote();
                    store.audit(quote, scope);
                    quote.setQuoteNo("QUOTE");
                    quote.setAdmissionNo("ADMISSION");
                    quote.setEntityNo("E");
                    quote.setStoreNo("S");
                    quote.setCurrency("AED");
                    quote.setSourceSnapshot(json.writeValueAsString(data));
                    quote.setSourceHash(ref.sourceHash());
                    quote.setSourceReceiptRef("SOURCE_READ");
                    quote.setMerchantReceivableMinor(100L);
                    quote.setCustomerPayableMinor(100L);
                    store.quotes.insert(quote);
                    return null;
                });
        store.ensureGuard(ref);
        when(source.demandPlan(any(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return buildPlan(call.getArgument(1));
                        });
        when(inventory.query(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new Receipt(
                                    call.getArgument(0), null, null, "ABSENT", 0, null, List.of());
                        });
        when(inventory.reserve(any(), anyLong(), anyInt()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var plan = (Plan) call.getArgument(0);
                            assertThat(
                                            store.query(
                                                    () ->
                                                            store.preparation(
                                                                            store.required(
                                                                                    ref, false),
                                                                            false)
                                                                    .getPlanSnapshot()))
                                    .isEqualTo(json.writeValueAsString(plan));
                            return receipt(plan, "HELD", BigDecimal.ZERO);
                        });
    }

    private Plan buildPlan(String reservationRef) {
        var stockScope = new InventoryReservationPort.Scope("MAIN", "E", reservationRef);
        var raw =
                new Plan(
                        stockScope,
                        "PLAN_"
                                + store.query(
                                        () ->
                                                store.preparation(store.required(ref, false), false)
                                                        .getGeneration()),
                        null,
                        List.of(
                                new Branch(
                                        "C",
                                        "SUB",
                                        "S",
                                        "FINISHED_SKU",
                                        quantity,
                                        "KG",
                                        List.of(
                                                new Leaf(
                                                        "L", "SKU", quantity, "KG", definition)))));
        originalPlan =
                new Plan(
                        stockScope,
                        raw.planNo(),
                        InventoryDemandPlanFacts.hash(raw),
                        raw.branches());
        return originalPlan;
    }

    Receipt receipt(Plan plan, String state, BigDecimal released) {
        return new Receipt(
                plan.scope(),
                plan.planNo(),
                plan.planHash(),
                state,
                3,
                "EXPIRED".equals(state) ? now.minusSeconds(1) : now.plusSeconds(900),
                List.of(
                        new Progress(
                                "L",
                                "C",
                                "SUB",
                                "FINISHED_SKU",
                                "NATIVE_STOCK_ITEM",
                                quantity,
                                BigDecimal.ZERO,
                                released,
                                "KG",
                                definition,
                                null,
                                null,
                                null,
                                null,
                                "UNKNOWN")));
    }

    @Test
    void originalIntentIsPersistedBeforeIndependentReservationAndOnlyOwnerDeadlineBecomesReady() {
        service.prepare(ref, source);
        var row = store.query(() -> store.required(ref, false));
        assertThat(row.getStatus()).isEqualTo("PREPARED");
        assertThat(row.getLeaseUntil()).isNull();
        var preparation = store.query(() -> store.preparation(row, false));
        assertThat(preparation.getGeneration()).isEqualTo(1);
        assertThat(preparation.getValidUntil().toInstant(ZoneOffset.UTC))
                .isEqualTo(now.plusSeconds(900));
        assertThat(preparation.getPreparationReceiptRef()).isNotBlank();
        assertThat(store.query(() -> store.quotes.selectCount(null))).isEqualTo(1);
    }

    @Test
    void aLatePlanBuilderCannotDispatchAfterCancellationFencesItsGeneration() {
        doAnswer(
                        call -> {
                            var validPlan = buildPlan(call.getArgument(1));
                            store.atomic(
                                    () -> {
                                        var row = store.required(ref, true);
                                        store.imports.update(
                                                Wrappers.<OrdChannelImport>update()
                                                        .eq("id", row.getId())
                                                        .set("status", "CANCELLING")
                                                        .set("fence", row.getFence() + 1));
                                        return null;
                                    });
                            return validPlan;
                        })
                .when(source)
                .demandPlan(any(), anyString(), anyString());
        assertThatCode(() -> service.prepare(ref, source)).doesNotThrowAnyException();
        verify(inventory, never()).reserve(any(), anyLong(), anyInt());
        verify(inventory, never()).query(any());
        assertThat(store.query(() -> store.required(ref, false).getStatus()))
                .isEqualTo("CANCELLING");
    }

    @Test
    void unknownReservationKeepsTheOriginalIntentAndCannotCreateAnotherGeneration() {
        doThrow(new IllegalStateException("stock response lost"))
                .when(inventory)
                .reserve(any(), anyLong(), anyInt());
        assertThatThrownBy(() -> service.prepare(ref, source))
                .isInstanceOf(IllegalStateException.class);
        var row = store.query(() -> store.required(ref, false));
        var preparation = store.query(() -> store.preparation(row, false));
        assertThat(row.getStatus()).isEqualTo("PREPARING");
        assertThat(preparation.getStatus()).isEqualTo("RESERVING");
        service.prepare(ref, source); // live original lease is not stolen
        assertThat(store.query(() -> store.preparations.selectCount(null))).isEqualTo(1);
        assertThat(
                        store.query(
                                () ->
                                        store.preparation(store.required(ref, false), false)
                                                .getReservationRef()))
                .isEqualTo(preparation.getReservationRef());
    }

    @Test
    void onlyARealExpiredFullyReleasedPlanCanCreateANewPreparationGeneration() {
        when(inventory.query(any())).thenAnswer(call -> receipt(originalPlan, "EXPIRED", quantity));
        service.prepare(ref, source);
        assertThat(store.query(() -> store.required(ref, false).getStatus()))
                .isEqualTo("WAITING_STOCK");
        var first = originalPlan;
        when(inventory.query(any()))
                .thenAnswer(
                        call ->
                                new Receipt(
                                        call.getArgument(0),
                                        null,
                                        null,
                                        "ABSENT",
                                        0,
                                        null,
                                        List.of()));
        service.prepare(ref, source);
        var preparation = store.query(() -> store.preparation(store.required(ref, false), false));
        assertThat(preparation.getGeneration()).isEqualTo(2);
        assertThat(originalPlan.scope()).isNotEqualTo(first.scope());
        assertThat(preparation.getPreviousPreparationNo()).isNotNull();
    }

    private CollectionFacts collectionFacts(Collection kind) {
        var original = store.query(() -> store.data(store.required(ref, false)));
        return new CollectionFacts(
                ref,
                original.externalOrderRef(),
                original.externalDocumentHash(),
                original.externalFinancialHash(),
                kind,
                kind == Collection.PLATFORM_COLLECTED ? 100L : null,
                original.currency(),
                kind == Collection.PLATFORM_COLLECTED ? "ACTUAL_PLATFORM_READ" : null,
                now,
                now.plusSeconds(25),
                "ORIGINAL_CURRENT_READ");
    }

    private void admittedCashPolicy(boolean platformAllowed) {
        store.atomic(
                () -> {
                    var policy = new OrdChannelPolicy();
                    store.audit(policy, scope);
                    policy.setPolicyNo("POLICY");
                    policy.setEntityNo("E");
                    policy.setStoreNo("S");
                    policy.setChannelStoreKey(definition);
                    policy.setCurrency("AED");
                    policy.setEnabled(true);
                    policy.setAllowCashOnDelivery(true);
                    policy.setAllowPlatformCollected(platformAllowed);
                    policy.setMaxSingleMinor(1000L);
                    policy.setMaxOutstandingMinor(1000L);
                    policy.setReservedMinor(100L);
                    policy.setHoldSeconds(900);
                    store.policies.insert(policy);
                    var row = store.required(ref, true);
                    row.setPolicySnapshot(
                            json.writeValueAsString(
                                    new IndustryChannelOrderStore.FundingGrant(
                                            "POLICY",
                                            0,
                                            "AED",
                                            100,
                                            900,
                                            collectionFacts(Collection.CASH_ON_DELIVERY))));
                    row.setFundingStatus("RESERVED_COD");
                    store.imports.updateById(row);
                    return null;
                });
    }

    @Test
    void actualCollectionPromotionRechecksMerchantPolicyAndPreservesSingleCreditReservation() {
        admittedCashPolicy(true);
        var original = store.query(() -> store.data(store.required(ref, false)));
        var promoted =
                store.reserveFunding(ref, original, collectionFacts(Collection.PLATFORM_COLLECTED));
        assertThat(promoted.getFundingStatus()).isEqualTo("RESERVED_PLATFORM");
        assertThat(store.query(() -> store.policies.selectOne(null).getReservedMinor()))
                .isEqualTo(100);
        var admitted =
                json.readValue(
                        promoted.getPolicySnapshot(), IndustryChannelOrderStore.FundingGrant.class);
        assertThat(admitted.originalCollection().collection())
                .isEqualTo(Collection.PLATFORM_COLLECTED);
        var history = store.query(() -> histories.selectOne(null));
        assertThat(history.getPreviousCollection()).isEqualTo("CASH_ON_DELIVERY");
        assertThat(
                        json.readValue(
                                        history.getPreviousSnapshot(),
                                        IndustryChannelOrderStore.FundingGrant.class)
                                .originalCollection()
                                .collection())
                .isEqualTo(Collection.CASH_ON_DELIVERY);
        assertThat(history.getOriginalCollectionHash())
                .isEqualTo(PricingSnapshotHash.of(admitted.originalCollection()));
        assertThat(store.query(() -> store.data(store.required(ref, false)))).isEqualTo(original);
        assertThat(
                        store.reserveFunding(
                                        ref,
                                        original,
                                        collectionFacts(Collection.PLATFORM_COLLECTED))
                                .getReservedMinor())
                .isEqualTo(100);
        assertThat(store.query(() -> histories.selectCount(null))).isEqualTo(1);
    }

    @Test
    void changedCollectionDoesNotBypassARejectedCurrentMerchantPolicy() {
        admittedCashPolicy(false);
        var original = store.query(() -> store.data(store.required(ref, false)));
        assertThatThrownBy(
                        () ->
                                store.reserveFunding(
                                        ref,
                                        original,
                                        collectionFacts(Collection.PLATFORM_COLLECTED)))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.query(() -> store.required(ref, false).getFundingStatus()))
                .isEqualTo("RESERVED_COD");
        assertThat(store.query(() -> store.policies.selectOne(null).getReservedMinor()))
                .isEqualTo(100);
    }

    @Test
    void aLiveConfirmationCannotChangeItsFrozenCollectionAuthority() {
        admittedCashPolicy(true);
        store.atomic(
                () -> {
                    var row = store.required(ref, true);
                    row.setStatus("CONFIRMING");
                    row.setLeaseUntil(store.now().plusMinutes(1));
                    store.imports.updateById(row);
                    return null;
                });
        var original = store.query(() -> store.data(store.required(ref, false)));
        assertThatThrownBy(
                        () ->
                                store.reserveFunding(
                                        ref,
                                        original,
                                        collectionFacts(Collection.PLATFORM_COLLECTED)))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.query(() -> store.required(ref, false).getFundingStatus()))
                .isEqualTo("RESERVED_COD");
    }
}
