package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdChannelPreparation;
import ai.neargo.shop.trade.entity.OrdChannelQuote;
import ai.neargo.shop.trade.mapper.ChannelAfterSaleMappers;
import ai.neargo.shop.trade.mapper.TradeMappers;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Map;
import java.util.UUID;

/**
 * Real native owner tables/transactions, isolated inventory input; does not claim a treasury or
 * vendor integration.
 */
class NativeChannelAfterSaleServiceTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final InventoryDemandPlanPort inventory = mock(InventoryDemandPlanPort.class);
    final IndustryChannelOrderPort.Scope scope =
            new IndustryChannelOrderPort.Scope("MAIN", "E", "S");
    NativeChannelAfterSaleService service;
    IndustryChannelOrderPort.Reference ref;
    JdbcTemplate sql;

    @BeforeEach
    void database() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:after_sale_"
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
                                "../shop-app/src/main/resources/db/migration/V611__native_channel_after_sale.sql"))
                .execute(ds);
        sql = new JdbcTemplate(ds);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper :
                List.of(
                        TradeMappers.ChannelImportMapper.class,
                        TradeMappers.ChannelQuoteMapper.class,
                        TradeMappers.ChannelAdmissionGuardMapper.class,
                        TradeMappers.ChannelPolicyMapper.class,
                        TradeMappers.ChannelPreparationMapper.class,
                        ChannelAfterSaleMappers.CaseMapper.class,
                        ChannelAfterSaleMappers.LineMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var store =
                new IndustryChannelOrderStore(
                        session.getMapper(TradeMappers.ChannelImportMapper.class),
                        session.getMapper(TradeMappers.ChannelAdmissionGuardMapper.class),
                        session.getMapper(TradeMappers.ChannelQuoteMapper.class),
                        session.getMapper(TradeMappers.ChannelPolicyMapper.class),
                        session.getMapper(TradeMappers.ChannelPreparationMapper.class),
                        json,
                        mock(DocumentNumberCoordinator.class),
                        new DataSourceTransactionManager(ds),
                        Clock.fixed(now, ZoneOffset.UTC));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("inventory", inventory);
        service =
                new NativeChannelAfterSaleService(
                        store,
                        session.getMapper(ChannelAfterSaleMappers.CaseMapper.class),
                        session.getMapper(ChannelAfterSaleMappers.LineMapper.class),
                        beans.getBeanProvider(InventoryDemandPlanPort.class));
        var qty = new BigDecimal("1.25");
        var component =
                new IndustryChannelOrderSourcePort.Component(
                        "LINE",
                        null,
                        "MAIN",
                        "SOURCE_LINE",
                        "COMPONENT",
                        "MAIN",
                        "G",
                        "SKU",
                        "Original",
                        null,
                        null,
                        qty,
                        "KG",
                        100,
                        0,
                        0,
                        100,
                        "ORIGINAL_ALLOCATION");
        var data =
                new IndustryChannelOrderSourcePort.Data(
                        scope,
                        "FOOD",
                        "SOURCE",
                        1,
                        "IMPORT",
                        new IndustryChannelOrderSourcePort.Channel(
                                "MOCK", "PP", "a".repeat(64), "EA", "ES", "CN"),
                        "EXTERNAL",
                        "b".repeat(64),
                        "ORIGINAL_READ",
                        "c".repeat(64),
                        "CNY",
                        IndustryChannelOrderSourcePort.Collection.PLATFORM_COLLECTED,
                        "UPSTREAM_ONLY",
                        IndustryChannelOrderSourcePort.Fulfillment.CHANNEL_DELIVERY,
                        new IndustryChannelOrderSourcePort.Party("PARTY", "CUSTOMER", "Guest"),
                        null,
                        now.minusSeconds(60),
                        List.of(
                                new IndustryChannelOrderSourcePort.InvoiceLine(
                                        "LINE", qty, "KG", 100, 0, 0, 100)),
                        List.of(component),
                        new IndustryChannelOrderSourcePort.Totals(100, 0, 0, 0, 100, 0, 0, 0, 100));
        ref =
                new IndustryChannelOrderPort.Reference(
                        scope, "FOOD", "SOURCE", 1, PricingSnapshotHash.of(data), "IMPORT");
        var raw =
                new InventoryDemandPlanPort.Plan(
                        new InventoryReservationPort.Scope("MAIN", "E", "RES"),
                        "PLAN",
                        null,
                        List.of(
                                new InventoryDemandPlanPort.Branch(
                                        "COMPONENT",
                                        "SUB",
                                        "S",
                                        "RECIPE_MATERIAL",
                                        qty,
                                        "KG",
                                        List.of(
                                                new InventoryDemandPlanPort.Leaf(
                                                        "LEAF",
                                                        "MATERIAL",
                                                        qty,
                                                        "KG",
                                                        "a".repeat(64))),
                                        "ORIGINAL_RECIPE")));
        var plan =
                new InventoryDemandPlanPort.Plan(
                        raw.scope(),
                        raw.planNo(),
                        InventoryDemandPlanFacts.hash(raw),
                        raw.branches());
        store.atomic(
                () -> {
                    var row = new OrdChannelImport();
                    store.audit(row, scope);
                    row.setAdmissionNo("ADMISSION");
                    row.setEntityNo("E");
                    row.setStoreNo("S");
                    row.setChannelStoreKey("a".repeat(64));
                    row.setExternalOrderKey("b".repeat(64));
                    row.setExternalOrderRef("EXTERNAL");
                    row.setSourceSystem("FOOD");
                    row.setSourceNo("SOURCE");
                    row.setSourceVersion(1L);
                    row.setSourceHash(ref.sourceHash());
                    row.setImportRef("IMPORT");
                    row.setQuoteNo("QUOTE");
                    row.setOrderNo("ORDER");
                    row.setSubOrderNo("SUB");
                    row.setStatus("ACCEPTED");
                    row.setFence(0L);
                    row.setFundingStatus("RESERVED_PLATFORM");
                    row.setAcceptanceReceiptRef("ACTUAL_CORE_ACCEPTANCE");
                    row.setPreparationNo("PREPARATION");
                    row.setAllocationsJson(
                            json.writeValueAsString(
                                    Map.of(
                                            "allocations",
                                            List.of(
                                                    new IndustryChannelOrderPort.Allocation(
                                                            "LINE",
                                                            null,
                                                            "MAIN",
                                                            "SOURCE_LINE",
                                                            "COMPONENT",
                                                            "OPAQUE_ITEM",
                                                            "1.25",
                                                            "KG",
                                                            100,
                                                            "CNY",
                                                            "ORIGINAL_ALLOCATION",
                                                            1)))));
                    store.imports.insert(row);
                    var quote = new OrdChannelQuote();
                    store.audit(quote, scope);
                    quote.setQuoteNo("QUOTE");
                    quote.setEntityNo("E");
                    quote.setStoreNo("S");
                    quote.setAdmissionNo("ADMISSION");
                    quote.setSourceHash(ref.sourceHash());
                    quote.setSourceSnapshot(json.writeValueAsString(data));
                    quote.setCurrency("CNY");
                    quote.setMerchantReceivableMinor(100L);
                    quote.setCustomerPayableMinor(100L);
                    quote.setSourceReceiptRef("ORIGINAL_READ");
                    store.quotes.insert(quote);
                    var preparation = new OrdChannelPreparation();
                    store.audit(preparation, scope);
                    preparation.setPreparationNo("PREPARATION");
                    preparation.setEntityNo("E");
                    preparation.setStoreNo("S");
                    preparation.setAdmissionNo("ADMISSION");
                    preparation.setGeneration(1L);
                    preparation.setReservationRef("RES");
                    preparation.setStatus("ACCEPTED_UNPAID");
                    preparation.setPlanSnapshot(json.writeValueAsString(plan));
                    preparation.setPlanHash(plan.planHash());
                    store.preparations.insert(preparation);
                    return null;
                });
        when(inventory.query(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return receipt(plan, new BigDecimal("0.75"), BigDecimal.ZERO);
                        });
        when(inventory.releaseTo(any(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            List<InventoryDemandPlanPort.Target> targets = call.getArgument(3);
                            assertThat(targets).hasSize(1);
                            assertThat(targets.getFirst().cumulativeQty())
                                    .isEqualByComparingTo("0.5");
                            return receipt(plan, new BigDecimal("0.75"), new BigDecimal("0.5"));
                        });
    }

    InventoryDemandPlanPort.Receipt receipt(
            InventoryDemandPlanPort.Plan plan, BigDecimal issued, BigDecimal released) {
        return new InventoryDemandPlanPort.Receipt(
                plan.scope(),
                plan.planNo(),
                plan.planHash(),
                "ACCEPTED_UNPAID",
                3,
                now.plusSeconds(900),
                List.of(
                        new InventoryDemandPlanPort.Progress(
                                "LEAF",
                                "COMPONENT",
                                "SUB",
                                "RECIPE_MATERIAL",
                                "MATERIAL",
                                new BigDecimal("1.25"),
                                issued,
                                released,
                                "KG",
                                "a".repeat(64),
                                null,
                                null,
                                null,
                                null,
                                "UNKNOWN")));
    }

    IndustryChannelAfterSalePort.Authorize request(String caseRef, String quantity) {
        return new IndustryChannelAfterSalePort.Authorize(
                ref,
                caseRef,
                IndustryChannelAfterSalePort.Action.REFUND_ONLY,
                "ORIGINAL_POLICY",
                List.of(
                        new IndustryChannelAfterSalePort.Selected(
                                "SOURCE_LINE", "COMPONENT", quantity)));
    }

    @Test
    void originalDecimalSelectionProducesBoundedNativeDecisionWithoutBuyerOrPaidTransition() {
        var facts = service.authorize(request("CASE1", "0.5"), "MERCHANT", "K1");
        assertThat(facts.decision().refundCeilingMinor()).isEqualTo(40);
        assertThat(facts.decision().lines().getFirst().physicalQuantity()).isEqualTo("0.5");
        assertThat(facts.fundsStatus()).isEqualTo("WAITING_FUNDS_OWNER");
        assertThat(facts.inventoryStatus()).isEqualTo("WAITING_ORIGINAL_ACTION");
        assertThat(sql.queryForObject("SELECT status FROM ord_channel_import", String.class))
                .isEqualTo("ACCEPTED");
        assertThat(
                        sql.queryForObject(
                                "SELECT funding_status FROM ord_channel_import", String.class))
                .isEqualTo("RESERVED_PLATFORM");
    }

    @Test
    void cumulativeCasesCannotExceedOriginalPhysicalOrMoneyCeiling() {
        service.authorize(request("CASE1", "0.5"), "MERCHANT", "K1");
        assertThatThrownBy(() -> service.authorize(request("CASE2", "0.750001"), "MERCHANT", "K2"))
                .isInstanceOf(RuntimeException.class);
        var remainder = service.authorize(request("CASE2", "0.75"), "MERCHANT", "K2");
        assertThat(remainder.decision().refundCeilingMinor()).isEqualTo(60);
        assertThat(
                        sql.queryForObject(
                                "SELECT SUM(refund_ceiling_minor) FROM ord_channel_after_sale",
                                Long.class))
                .isEqualTo(100L);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ord_channel_after_sale", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void sameRequestReplaysOriginalDecisionAndConflictingActorCannotReuseKey() {
        var first = service.authorize(request("CASE1", "0.5"), "MERCHANT", "K1");
        assertThat(service.authorize(request("CASE1", "0.5"), "MERCHANT", "K1").decision())
                .isEqualTo(first.decision());
        assertThatThrownBy(() -> service.authorize(request("CASE1", "0.5"), "OTHER", "K1"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_channel_after_sale_line", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anotherStoreRevisionOrCoreOrderCannotBorrowDecision() {
        var first = service.authorize(request("CASE1", "0.5"), "MERCHANT", "K1");
        var q = first.query();
        for (var wrong :
                List.of(
                        new IndustryChannelAfterSalePort.Query(
                                new IndustryChannelOrderPort.Scope("MAIN", "E", "OTHER"),
                                q.sourceSystem(),
                                q.coreOrderNo(),
                                q.externalCaseRef(),
                                q.decisionNo(),
                                q.revisionNo()),
                        new IndustryChannelAfterSalePort.Query(
                                q.scope(),
                                q.sourceSystem(),
                                "OTHER",
                                q.externalCaseRef(),
                                q.decisionNo(),
                                q.revisionNo()),
                        new IndustryChannelAfterSalePort.Query(
                                q.scope(),
                                q.sourceSystem(),
                                q.coreOrderNo(),
                                q.externalCaseRef(),
                                q.decisionNo(),
                                "d".repeat(64))))
            assertThatThrownBy(() -> service.get(wrong)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void issuedRecipeMaterialIsNeverAutomaticallyRestockedDuringRefundOnly() {
        var facts = service.authorize(request("CASE1", "1.25"), "MERCHANT", "K1");
        var result = service.releaseUnissued(facts.query(), "MERCHANT");
        assertThat(result.inventoryStatus()).isEqualTo("WAITING_ISSUED_RETURN_OWNER");
        assertThat(result.fundsStatus()).isEqualTo("WAITING_FUNDS_OWNER");
        verify(inventory).releaseTo(any(), anyString(), anyString(), any());
        verify(inventory, never()).reverse(any(), anyString(), anyString(), anyString());
        verify(inventory, never()).supplement(any(), anyString(), anyString(), any());
    }
}
