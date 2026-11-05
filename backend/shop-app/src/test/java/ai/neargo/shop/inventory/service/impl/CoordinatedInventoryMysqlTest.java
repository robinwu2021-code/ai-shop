package ai.neargo.shop.inventory.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.InventoryDataSourceConfig;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ExecutionRetentionClaimMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ExecutionRetentionMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SupplierMapper;
import ai.neargo.inventory.domain.mapper.RefundRestoreMapper;
import ai.neargo.inventory.domain.mapper.ReturnReceiptMapper;
import ai.neargo.inventory.domain.policy.ExecutionStockRetentionGuard;
import ai.neargo.inventory.domain.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.domain.service.impl.CoordinatedOperationServiceImpl;
import ai.neargo.inventory.domain.service.impl.InboundServiceImpl;
import ai.neargo.inventory.domain.service.impl.InventoryMutationGuard;
import ai.neargo.inventory.domain.service.impl.OutboundServiceImpl;
import ai.neargo.inventory.domain.service.impl.ReservationServiceImpl;
import ai.neargo.inventory.domain.service.impl.StockPostingServiceImpl;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.shop.inventory.service.CoordinatedInventoryService;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * 协同库存操作（商城硬件 SKU）在真 MySQL 上的回归：从 codex/commerce-foundation 移植，
 * 改到统一线的库存领域（ADR-046）与生命周期 4。
 *
 * <p>每个用例新建一个 {@code commerce_test_<uuid>} 库，跑真实的库存迁移 V1–V28，用完即删。
 * 需要 {@code COMMERCE_MYSQL_URL / _USER / _PASSWORD}（URL 形如 {@code jdbc:mysql://127.0.0.1:3306/?…}）。
 */
@EnabledIfEnvironmentVariable(named = "COMMERCE_MYSQL_URL", matches = ".+")
class CoordinatedInventoryMysqlTest {
    @Test
    void legacyMutatorsCannotChangeCoordinatedOwnershipAndCorruptionIsNotCertified() {
        try (var f = new Fixture()) {
            var service = f.service();
            var ref = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "owner-fence");
            service.reserve(
                    new CoordinatedInventoryService.Reserve(
                            ref,
                            "r",
                            new CoordinatedInventoryService.Binding("P", "I", "L", "v1"),
                            5));
            String external =
                    f.jdbc.queryForObject("SELECT external_ref FROM inv_reservation", String.class);
            assertEquals(
                    ReservationService.LIFECYCLE_COORDINATED,
                    f.jdbc.queryForObject(
                            "SELECT lifecycle_version FROM inv_reservation", Integer.class));
            f.jdbc.update("UPDATE inv_reservation_line SET fulfillment_ref='legacy-fulfillment'");
            List<Runnable> legacy =
                    List.of(
                            () -> f.reservations.release("P", external),
                            () -> f.reservations.releaseByRef(external),
                            () -> f.reservations.cancelByRef("P", external),
                            () -> f.reservations.releaseAllocatedByRef(external),
                            () ->
                                    f.reservations.releaseAllocatedByFulfillmentRef(
                                            "legacy-fulfillment"),
                            () ->
                                    f.reservations.issueByFulfillmentRef(
                                            "legacy-fulfillment", "legacy"),
                            () ->
                                    f.reservations.issue(
                                            "P", external, "legacy-fulfillment", "legacy"),
                            () -> f.reservations.allocatePaidByRef(external),
                            () -> f.reservations.allocatePaid("P", external),
                            () -> f.reservations.acceptUnpaid("P", external),
                            () -> f.reservations.commit("P", external, "legacy"),
                            () -> f.reservations.commitByRef(external, "legacy"));
            for (String state :
                    List.of(
                            "HELD",
                            "ALLOCATED",
                            InvEnums.ReservationStatus.PARTIALLY_COMMITTED)) {
                f.jdbc.update("UPDATE inv_reservation SET status=?", state);
                for (Runnable mutation : legacy) {
                    var thrown =
                            assertThrows(
                                    InventoryException.class,
                                    () -> f.tx.executeWithoutResult(tx -> mutation.run()));
                    assertEquals(
                            ai.neargo.inventory.api.InventoryError.CONFLICT, thrown.error());
                    assertEquals(
                            5,
                            f.jdbc.queryForObject(
                                    "SELECT reserved FROM inv_stock_balance WHERE owner_id='P'",
                                    Integer.class));
                    assertEquals(
                            state,
                            f.jdbc.queryForObject(
                                    "SELECT status FROM inv_reservation", String.class));
                    assertEquals(
                            0,
                            f.jdbc.queryForObject(
                                    "SELECT issued_qty+released_qty FROM inv_reservation_line",
                                    Integer.class));
                }
            }
            for (String corrupt : List.of("RELEASED", "EXPIRED", "COMMITTED", "ALLOCATED")) {
                f.jdbc.update("UPDATE inv_reservation SET status=?", corrupt);
                assertThrows(RuntimeException.class, () -> service.query(ref, "r"));
            }
            f.jdbc.update("UPDATE inv_reservation SET status='HELD',lifecycle_version=1");
            assertThrows(RuntimeException.class, () -> service.query(ref, "r"));
            f.jdbc.update("UPDATE inv_reservation SET lifecycle_version=3");
            assertThrows(RuntimeException.class, () -> service.query(ref, "r"));
            f.jdbc.update("UPDATE inv_reservation SET lifecycle_version=4");
            assertEquals(5, service.query(ref, "r").held());
            service.ship(
                    new CoordinatedInventoryService.Shipment(
                            ref, "s", 2, "carrier", "tracking", "ops"));
            for (Runnable mutation : legacy) {
                assertThrows(
                        InventoryException.class,
                        () -> f.tx.executeWithoutResult(tx -> mutation.run()));
            }
            assertEquals(3, service.query(ref, "r").held());
            assertEquals(0, service.release(ref, "r").held());
            assertEquals(0, service.release(ref, "r").held());
        }
    }

    @Test
    void externallyCoordinatedHoldDoesNotExpireWhilePaymentIsUnknown() {
        try (var f = new Fixture()) {
            // 生命周期 4 只能经专门入口建，通用 reserve（含库存服务 HTTP 入参）传 4 一律拒
            var refused =
                    assertThrows(
                            InventoryException.class,
                            () ->
                                    f.tx.execute(
                                            s ->
                                                    f.reservations.reserve(
                                                            "P",
                                                            "external-0",
                                                            List.of(
                                                                    new ReservationService.Line(
                                                                            "I", "L", 1)),
                                                            60,
                                                            ReservationService
                                                                    .LIFECYCLE_COORDINATED)));
            assertEquals(ai.neargo.inventory.api.InventoryError.BAD_REQUEST, refused.error());
            String reservation =
                    assertDoesNotThrow(
                            () ->
                                    f.tx.execute(
                                            s ->
                                                    f.reservations.reserveCoordinated(
                                                            "P",
                                                            "external-1",
                                                            new ReservationService.Line(
                                                                    "I", "L", 5))));
            assertNotNull(reservation);
            // 落库即「过期」（ttl 0），仍不进回收
            f.jdbc.update(
                    "UPDATE inv_reservation SET expires_at=DATE_SUB(NOW(), INTERVAL 1 DAY)");
            assertEquals(0, f.reservations.countOverdue());
            assertEquals(Integer.valueOf(0), f.tx.execute(s -> f.reservations.expireOverdue(100)));
            assertEquals(
                    5,
                    f.jdbc.queryForObject(
                            "SELECT reserved FROM inv_stock_balance WHERE owner_id='P'",
                            Integer.class));
            assertThrows(
                    RuntimeException.class,
                    () -> f.tx.execute(s -> f.reservations.commit("P", "external-1", "legacy")));
        }
    }

    @Test
    void partialShipmentProofAndReleaseFenceUseOriginalBindingAndOneStockLedger() {
        try (var f = new Fixture()) {
            var service = f.service();
            var ref = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "component.1");
            var binding = new CoordinatedInventoryService.Binding("P", "I", "L", "mapping.v1");
            var reserve = new CoordinatedInventoryService.Reserve(ref, "reserve.1", binding, 5);
            var held = assertDoesNotThrow(() -> service.reserve(reserve));
            assertEquals(5, held.held());
            assertEquals(5, held.operationQuantity());
            assertEquals(held, service.reserve(reserve));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.reserve(
                                    new CoordinatedInventoryService.Reserve(
                                            ref,
                                            "reserve.1",
                                            new CoordinatedInventoryService.Binding(
                                                    "B", "I", "L", "mapping.v2"),
                                            5)));
            var ship =
                    new CoordinatedInventoryService.Shipment(
                            ref, "ship.1", 2, "carrier", "tracking.1", "ops");
            var first = service.ship(ship);
            assertEquals(2, first.operationQuantity());
            assertNotNull(first.documentNo());
            assertEquals(first, service.ship(ship));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.ship(
                                    new CoordinatedInventoryService.Shipment(
                                            ref, "ship.1", 3, "carrier", "tracking.1", "ops")));
            service.ship(
                    new CoordinatedInventoryService.Shipment(
                            ref, "ship.2", 2, "carrier", "tracking.2", "ops"));
            var proof = service.query(ref, "ship.1");
            assertEquals(2, proof.operationQuantity());
            assertEquals(4, proof.issued());
            assertEquals(first.documentNo(), proof.documentNo());
            var released = service.release(ref, "reserve.1");
            assertEquals(0, released.held());
            assertEquals(4, released.issued());
            assertEquals(
                    CoordinatedInventoryService.Status.RELEASED, service.reserve(reserve).status());
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.ship(
                                    new CoordinatedInventoryService.Shipment(
                                            ref, "ship.3", 1, "carrier", "tracking.3", "ops")));
            assertEquals(
                    16,
                    f.jdbc.queryForObject(
                            "SELECT on_hand FROM inv_stock_balance WHERE owner_id='P'",
                            Integer.class));
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT reserved FROM inv_stock_balance WHERE owner_id='P'",
                            Integer.class));
            assertEquals(
                    20,
                    f.jdbc.queryForObject(
                            "SELECT on_hand FROM inv_stock_balance WHERE owner_id='B'",
                            Integer.class));
            assertEquals(
                    2,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM inv_ledger WHERE owner_id='P'", Integer.class));
            assertEquals(
                    2,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM inv_outbound_order WHERE status='POSTED'",
                            Integer.class));
            assertEquals(
                    "PARTIAL_RELEASED",
                    f.jdbc.queryForObject("SELECT status FROM inv_reservation", String.class));
            var absent = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "late");
            assertEquals(
                    CoordinatedInventoryService.Status.NOT_FOUND,
                    service.query(absent, "r").status());
            assertEquals(
                    CoordinatedInventoryService.Status.RELEASED,
                    service.release(absent, "r").status());
            assertEquals(
                    CoordinatedInventoryService.Status.RELEASED,
                    service.reserve(
                                    new CoordinatedInventoryService.Reserve(
                                            absent, "r", binding, 3))
                            .status());
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT reserved FROM inv_stock_balance WHERE owner_id='P'",
                            Integer.class));
        }
    }

    @Test
    void concurrentReleaseAndReserveAlwaysLeaveADurableFenceWithoutLeakingStock()
            throws Exception {
        try (var f = new Fixture();
                var pool = Executors.newFixedThreadPool(2)) {
            var service = f.service();
            var ref = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "race");
            var command =
                    new CoordinatedInventoryService.Reserve(
                            ref,
                            "r",
                            new CoordinatedInventoryService.Binding("P", "I", "L", "v1"),
                            3);
            var start = new CountDownLatch(1);
            var reserve =
                    pool.submit(
                            () -> {
                                start.await();
                                return service.reserve(command);
                            });
            var release =
                    pool.submit(
                            () -> {
                                start.await();
                                return service.release(ref, "r");
                            });
            start.countDown();
            reserve.get(10, TimeUnit.SECONDS);
            release.get(10, TimeUnit.SECONDS);
            assertEquals(
                    CoordinatedInventoryService.Status.RELEASED, service.reserve(command).status());
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT reserved FROM inv_stock_balance WHERE owner_id='P'",
                            Integer.class));
        }
    }

    @Test
    void invalidCommandsAreTranslatedToShopErrorsWithoutTouchingStock() {
        try (var f = new Fixture()) {
            var service = f.service();
            var ref = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "bad");
            var unknownItem = new CoordinatedInventoryService.Binding("P", "NOPE", "L", "v1");
            var e =
                    assertThrows(
                            ai.neargo.shop.common.BizException.class,
                            () ->
                                    service.reserve(
                                            new CoordinatedInventoryService.Reserve(
                                                    ref, "r", unknownItem, 1)));
            assertEquals(ai.neargo.shop.common.ErrorCode.NOT_FOUND, e.errorCode());
            assertThrows(
                    ai.neargo.shop.common.BizException.class,
                    () ->
                            service.reserve(
                                    new CoordinatedInventoryService.Reserve(
                                            ref,
                                            "r",
                                            new CoordinatedInventoryService.Binding(
                                                    "P", "I", "L", "v1"),
                                            0)));
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM inv_coordinated_reservation", Integer.class));
        }
    }

    static final class Fixture implements AutoCloseable {
        final String schema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final AnnotationConfigApplicationContext context;
        final DataSource ds;
        final JdbcTemplate jdbc;
        final PlatformTransactionManager manager;
        final TransactionTemplate tx;
        final ReservationServiceImpl reservations;
        final OutboundServiceImpl outbound;

        Fixture() {
            String url = System.getenv("COMMERCE_MYSQL_URL"),
                    user = System.getenv("COMMERCE_MYSQL_USER"),
                    pass = System.getenv("COMMERCE_MYSQL_PASSWORD");
            assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"));
            admin = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));
            admin.execute("CREATE DATABASE " + schema);
            context = new AnnotationConfigApplicationContext();
            try {
                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(
                                new MapPropertySource(
                                        "coordinated-inventory",
                                        Map.of(
                                                "inventory.enabled", "true",
                                                "inventory.flyway-enabled", "true",
                                                "inventory.datasource.url",
                                                        url.replace("/?", "/" + schema + "?"),
                                                "inventory.datasource.username", user,
                                                "inventory.datasource.password", pass)));
                context.register(InventoryDataSourceConfig.class);
                context.refresh();
            } catch (RuntimeException e) {
                close();
                throw e;
            }
            ds = context.getBean("invDataSource", DataSource.class);
            jdbc = new JdbcTemplate(ds);
            manager = context.getBean("invTransactionManager", PlatformTransactionManager.class);
            tx = new TransactionTemplate(manager);
            var sql = new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));
            var retention =
                    new ExecutionStockRetentionGuard(
                            sql.getMapper(ExecutionRetentionMapper.class),
                            sql.getMapper(ExecutionRetentionClaimMapper.class),
                            new ExecutionStockRetentionSchemaVerifier(
                                    ds, "inv_flyway_history", null),
                            sql.getMapper(ReservationMapper.class),
                            sql.getMapper(ReservationLineMapper.class),
                            sql.getMapper(OutboundOrderMapper.class),
                            sql.getMapper(RefundRestoreMapper.class),
                            sql.getMapper(ReturnReceiptMapper.class));
            var balances = sql.getMapper(BalanceMapper.class);
            var posting =
                    new StockPostingServiceImpl(
                            balances,
                            sql.getMapper(LedgerMapper.class),
                            sql.getMapper(OutboxMapper.class),
                            retention);
            var guard =
                    new InventoryMutationGuard(
                            sql.getMapper(StockCountMapper.class),
                            sql.getMapper(StockCountLineMapper.class),
                            sql.getMapper(LocationMapper.class));
            outbound =
                    new OutboundServiceImpl(
                            sql.getMapper(OutboundOrderMapper.class),
                            sql.getMapper(OutboundLineMapper.class),
                            sql.getMapper(ItemMapper.class),
                            sql.getMapper(SupplierMapper.class),
                            posting,
                            guard,
                            retention);
            var inbound =
                    new InboundServiceImpl(
                            sql.getMapper(InboundOrderMapper.class),
                            sql.getMapper(InboundLineMapper.class),
                            sql.getMapper(ItemMapper.class),
                            posting,
                            guard);
            reservations =
                    new ReservationServiceImpl(
                            sql.getMapper(ReservationMapper.class),
                            sql.getMapper(ReservationLineMapper.class),
                            balances,
                            outbound,
                            inbound,
                            retention);
            reservations.configurePlanExclusion(ds);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    reservations, "maxTtlSeconds", 86400L);
            for (String owner : List.of("P", "B")) {
                jdbc.update("INSERT INTO inv_owner(owner_id,name) VALUES (?,?)", owner, owner);
                jdbc.update(
                        "INSERT INTO inv_location(owner_id,location_id,name) VALUES"
                            + " (?,'L','warehouse')",
                        owner);
                jdbc.update(
                        "INSERT INTO inv_item(owner_id,item_id,name) VALUES (?,'I','device')",
                        owner);
                jdbc.update(
                        "INSERT INTO"
                            + " inv_stock_balance(owner_id,item_id,location_id,on_hand,reserved)"
                            + " VALUES (?,'I','L',20,0)",
                        owner);
            }
        }

        /** 宿主契约 → 领域：走的是生产里那两层，不是直接调领域 */
        CoordinatedInventoryService service() {
            return new CoordinatedInventoryServiceImpl(
                    new CoordinatedOperationServiceImpl(ds, manager, reservations, outbound));
        }

        @Override
        public void close() {
            try {
                context.close();
            } finally {
                admin.execute("DROP DATABASE IF EXISTS " + schema);
            }
        }
    }
}
