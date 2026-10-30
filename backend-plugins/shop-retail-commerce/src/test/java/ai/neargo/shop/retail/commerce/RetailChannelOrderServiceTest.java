package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.ImportGuardMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.OrderSourceMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.PartyMapper;
import ai.neargo.shop.retail.commerce.service.impl.RetailChannelOrderServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actual V610 schema and MyBatis transactions persist original supplier/core evidence; core RPC is
 * the owner fixture.
 */
class RetailChannelOrderServiceTest {
    RetailChannelOrderFixtures f;
    JdbcTemplate db;
    RetailChannelOrderServiceImpl service;
    IndustryChannelOrderPort core;
    final AtomicReference<Preparation> preparation = new AtomicReference<>();

    @BeforeEach
    void nativePersistence() throws Exception {
        f = new RetailChannelOrderFixtures();
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:retail_channel_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        String ddl =
                migration()
                        .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)))
                .execute(ds);
        var config = new MybatisConfiguration();
        config.addMapper(ImportGuardMapper.class);
        config.addMapper(OrderSourceMapper.class);
        config.addMapper(PartyMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        core = mock(IndustryChannelOrderPort.class);
        service =
                new RetailChannelOrderServiceImpl(
                        sql.getMapper(ImportGuardMapper.class),
                        sql.getMapper(OrderSourceMapper.class),
                        sql.getMapper(PartyMapper.class),
                        f.reader,
                        core,
                        f.materials,
                        f.json,
                        f.clock,
                        new DataSourceTransactionManager(ds));
        when(core.get(any())).thenAnswer(a -> preparation.get());
        when(core.prepare(any()))
                .thenAnswer(
                        a -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            IndustryChannelOrderPort.Reference ref = a.getArgument(0);
                            var source = service.capture(ref);
                            assertThat(source.data().components().getFirst().netMinor())
                                    .isEqualTo(189);
                            var plan =
                                    service.demandPlan(
                                            ref, "ORIGINAL_RESERVATION", "ORIGINAL_STORE_ORDER");
                            var actual =
                                    new Preparation(
                                            ref,
                                            Status.PREPARED,
                                            "ORIGINAL_CORE_ORDER",
                                            "ORIGINAL_STORE_ORDER",
                                            "ORIGINAL_PREPARATION",
                                            f.now.plusSeconds(15),
                                            "READY",
                                            plan,
                                            "ORIGINAL_CORE_QUOTE");
                            preparation.set(actual);
                            return actual;
                        });
        when(core.confirm(any()))
                .thenAnswer(
                        a -> {
                            var prepared = preparation.get();
                            var data = service.capture(prepared.reference()).data();
                            preparation.set(
                                    new Preparation(
                                            prepared.reference(),
                                            Status.ACCEPTED,
                                            prepared.orderNo(),
                                            prepared.subOrderNo(),
                                            prepared.preparationReceiptRef(),
                                            prepared.validUntil(),
                                            prepared.fundingStatus(),
                                            prepared.inventoryPlan(),
                                            prepared.quoteNo()));
                            return new Acceptance(
                                    prepared.reference(),
                                    prepared.orderNo(),
                                    prepared.subOrderNo(),
                                    "ORIGINAL_ACCEPTANCE",
                                    "READY",
                                    data.components().stream()
                                            .map(
                                                    component ->
                                                            new Allocation(
                                                                    component.externalLineRef(),
                                                                    null,
                                                                    "MAIN",
                                                                    component.sourceLineRef(),
                                                                    component.componentRef(),
                                                                    "ORIGINAL_OPAQUE_ITEM",
                                                                    component
                                                                            .physicalQuantity()
                                                                            .toPlainString(),
                                                                    component.unit(),
                                                                    component.netMinor(),
                                                                    data.currency(),
                                                                    "ORIGINAL_ALLOCATION",
                                                                    41))
                                            .toList());
                        });
        when(core.cancel(any()))
                .thenAnswer(
                        a ->
                                new IndustryChannelOrderPort.Cancellation(
                                        a.getArgument(0), "ORIGINAL_CANCEL"));
    }

    @Test
    void stableOriginalSourcePartyAndCoreQuoteNeverCreateOrdinaryCheckoutOrFoodDocuments() {
        var first = service.prepareImport(f.command);
        var second = service.getImport(f.reference);
        assertThat(first.status()).isEqualTo(ChannelOrderIngestPort.Status.PREPARED);
        assertThat(second.preparationReceiptRef()).isEqualTo("ORIGINAL_PREPARATION");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM retail_channel_order_source", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM retail_channel_party", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT party_no FROM retail_channel_party", String.class))
                .startsWith("RCP");
        var actual =
                f.json.readValue(
                        db.queryForObject(
                                "SELECT preparation_snapshot FROM retail_channel_order_source",
                                String.class),
                        Preparation.class);
        assertThat(actual.quoteNo()).isEqualTo("ORIGINAL_CORE_QUOTE");
        assertThat(actual.inventoryPlan().branches().getFirst().leaves().getFirst().quantity())
                .isEqualByComparingTo("2");
        assertThat(actual.inventoryPlan().branches().getFirst().unit()).isEqualTo("PIECE");
    }

    @Test
    void acceptedReceiptReplaysActualCoreStoreOrderAndOriginalOpaqueAllocation() {
        service.prepareImport(f.command);
        f.state.set(ChannelOrderGateway.State.ACCEPTED);
        var confirmation =
                new ChannelOrderIngestPort.Confirmation(f.reference, "UNTRUSTED_CALLER_RECEIPT");
        var accepted = service.confirmAcceptance(confirmation);
        assertThat(accepted.businessDocumentNo()).isEqualTo("ORIGINAL_STORE_ORDER");
        assertThat(accepted.acceptanceReceiptRef()).isEqualTo("ORIGINAL_ACCEPTANCE");
        assertThat(accepted.allocations().getFirst().coreItemRef())
                .isEqualTo("ORIGINAL_OPAQUE_ITEM");
        assertThat(accepted.allocations().getFirst().amountMinor()).isEqualTo(189);
        assertThat(service.confirmAcceptance(confirmation)).isEqualTo(accepted);
        assertThat(service.getImport(f.reference).status())
                .isEqualTo(ChannelOrderIngestPort.Status.ACCEPTED);
    }

    @Test
    void cancellationBeforeRegistrationPersistsOnlyRealWithdrawalGuard() {
        f.state.set(ChannelOrderGateway.State.CANCELLED);
        var first =
                service.cancelPreparedImport(
                        f.reference, "Caller reason is not withdrawal authority");
        assertThat(service.cancelPreparedImport(f.reference, "retry")).isEqualTo(first);
        assertThat(service.prepareImport(f.command).status())
                .isEqualTo(ChannelOrderIngestPort.Status.CANCELLED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM retail_channel_party", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM retail_channel_order_source", Integer.class))
                .isZero();
        verify(core, never()).prepare(any());
    }

    @Test
    void
            callerCannotCancelWithoutActualSupplierWithdrawalAndAcceptedCoreCannotBePretendedCancelled() {
        service.prepareImport(f.command);
        assertThatThrownBy(() -> service.cancelPreparedImport(f.reference, "cancel"))
                .isInstanceOf(RuntimeException.class);
        f.state.set(ChannelOrderGateway.State.ACCEPTED);
        service.confirmAcceptance(new ChannelOrderIngestPort.Confirmation(f.reference, "CALLER"));
        f.state.set(ChannelOrderGateway.State.CANCELLED);
        assertThatThrownBy(() -> service.cancelPreparedImport(f.reference, "cancel accepted"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM retail_channel_order_source", String.class))
                .isEqualTo("ACCEPTED");
        verify(core, never()).cancel(any());
    }

    private static String migration() throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            var candidate =
                    cursor.resolve(
                            "backend/shop-app/src/main/resources/db/migration/V610__retail_original_channel_order_sources.sql");
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            cursor = cursor.getParent();
        }
        throw new IOException(
                "Actual V610 migration source is required for the retail native fixture");
    }
}
