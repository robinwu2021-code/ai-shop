package ai.neargo.shop.inventory.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.inventory.mapper.InventoryMappers.*;
import ai.neargo.shop.inventory.service.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

class CoordinatedInventoryMysqlTest {
    @Test
    void legacyMutatorsCannotChangeCoordinatedOwnershipAndCorruptionIsNotCertified()
            throws Exception {
        try (var f = new Fixture()) {
            var service =
                    new CoordinatedInventoryServiceImpl(
                            f.ds, f.manager, f.reservations, f.outbound);
            var ref = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "owner-fence");
            service.reserve(
                    new CoordinatedInventoryService.Reserve(
                            ref,
                            "r",
                            new CoordinatedInventoryService.Binding("P", "I", "L", "v1"),
                            5));
            String external =
                    f.jdbc.queryForObject("SELECT external_ref FROM inv_reservation", String.class);
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
                            () -> f.reservations.allocatePaidByRef(external));
            for (String state :
                    List.of(
                            "HELD",
                            "ALLOCATED",
                            ai.neargo.shop.inventory.support.InvEnums.ReservationStatus
                                    .PARTIALLY_COMMITTED)) {
                f.jdbc.update("UPDATE inv_reservation SET status=?", state);
                for (Runnable mutation : legacy) {
                    try {
                        f.tx.executeWithoutResult(tx -> mutation.run());
                    } catch (RuntimeException expected) {
                    }
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
            assertEquals(5, service.query(ref, "r").held());
            service.ship(
                    new CoordinatedInventoryService.Shipment(
                            ref, "s", 2, "carrier", "tracking", "ops"));
            for (Runnable mutation : legacy) {
                try {
                    f.tx.executeWithoutResult(tx -> mutation.run());
                } catch (RuntimeException expected) {
                }
            }
            assertEquals(3, service.query(ref, "r").held());
            assertEquals(0, service.release(ref, "r").held());
            assertEquals(0, service.release(ref, "r").held());
        }
    }

    @Test
    void externallyCoordinatedHoldDoesNotExpireWhilePaymentIsUnknown() throws Exception {
        try (var f = new Fixture()) {
            String reservation =
                    assertDoesNotThrow(
                            () ->
                                    f.tx.execute(
                                            s ->
                                                    f.reservations.reserve(
                                                            "P",
                                                            "external-1",
                                                            List.of(
                                                                    new ReservationService.Line(
                                                                            "I", "L", 5)),
                                                            -1,
                                                            3)));
            assertNotNull(reservation);
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
    void partialShipmentProofAndReleaseFenceUseOriginalBindingAndOneStockLedger() throws Exception {
        try (var f = new Fixture()) {
            var service =
                    new CoordinatedInventoryServiceImpl(
                            f.ds, f.manager, f.reservations, f.outbound);
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
                    2, f.jdbc.queryForObject("SELECT count(*) FROM inv_ledger", Integer.class));
            assertEquals(
                    2,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM inv_outbound_order WHERE status='POSTED'",
                            Integer.class));
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
    void concurrentReleaseAndReserveAlwaysLeaveADurableFenceWithoutLeakingStock() throws Exception {
        try (var f = new Fixture();
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var service =
                    new CoordinatedInventoryServiceImpl(
                            f.ds, f.manager, f.reservations, f.outbound);
            var ref = new CoordinatedInventoryService.Reference("PLATFORM_MALL", "race");
            var command =
                    new CoordinatedInventoryService.Reserve(
                            ref,
                            "r",
                            new CoordinatedInventoryService.Binding("P", "I", "L", "v1"),
                            3);
            var start = new java.util.concurrent.CountDownLatch(1);
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
            reserve.get(10, java.util.concurrent.TimeUnit.SECONDS);
            release.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    CoordinatedInventoryService.Status.RELEASED, service.reserve(command).status());
            assertEquals(
                    0,
                    f.jdbc.queryForObject(
                            "SELECT reserved FROM inv_stock_balance WHERE owner_id='P'",
                            Integer.class));
        }
    }

    static final class Fixture implements AutoCloseable {
        final String schema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "");
        final javax.sql.DataSource ds;
        final JdbcTemplate admin, jdbc;
        final DataSourceTransactionManager manager;
        final TransactionTemplate tx;
        final ReservationService reservations;
        final OutboundService outbound;

        Fixture() throws Exception {
            String url = System.getenv("COMMERCE_MYSQL_URL"),
                    user = System.getenv("COMMERCE_MYSQL_USER"),
                    pass = System.getenv("COMMERCE_MYSQL_PASSWORD");
            assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"));
            admin = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));
            admin.execute("CREATE DATABASE " + schema);
            ds = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), user, pass);
            jdbc = new JdbcTemplate(ds);
            manager = new DataSourceTransactionManager(ds);
            tx = new TransactionTemplate(manager);
            var ddl =
                    new ClassPathResource("db/inventory-h2/V1__inventory_baseline.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
            for (String table :
                    List.of(
                            "inv_owner",
                            "inv_location",
                            "inv_item",
                            "inv_stock_balance",
                            "inv_reservation",
                            "inv_reservation_line",
                            "inv_outbound_order",
                            "inv_outbound_line",
                            "inv_ledger",
                            "inv_outbox",
                            "inv_stock_count",
                            "inv_stock_count_line",
                            "inv_supplier",
                            "inv_coordinated_reservation",
                            "inv_coordinated_shipment")) {
                var match =
                        Pattern.compile(
                                        "(?s)CREATE TABLE IF NOT EXISTS "
                                                + table
                                                + "\\s*\\(.*?\n\\);")
                                .matcher(ddl);
                assertTrue(match.find(), table);
                jdbc.execute(match.group());
            }
            var config = new MybatisConfiguration();
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(config);
            factory.setDataSource(ds);
            var global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig());
            global.setMetaObjectHandler(
                    new com.baomidou.mybatisplus.core.handlers.MetaObjectHandler() {
                        public void insertFill(org.apache.ibatis.reflection.MetaObject m) {
                            strictInsertFill(
                                    m, "createdAt", LocalDateTime.class, LocalDateTime.now());
                            strictInsertFill(
                                    m, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                        }

                        public void updateFill(org.apache.ibatis.reflection.MetaObject m) {
                            strictUpdateFill(
                                    m, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                        }
                    });
            factory.setGlobalConfig(global);
            var sql = new SqlSessionTemplate(factory.getObject());
            for (var mapper :
                    List.of(
                            ReservationMapper.class,
                            ReservationLineMapper.class,
                            BalanceMapper.class,
                            OutboundOrderMapper.class,
                            OutboundLineMapper.class,
                            ItemMapper.class,
                            SupplierMapper.class,
                            LedgerMapper.class,
                            OutboxMapper.class,
                            StockCountMapper.class,
                            StockCountLineMapper.class,
                            LocationMapper.class)) config.addMapper(mapper);
            var posting =
                    new StockPostingServiceImpl(
                            sql.getMapper(BalanceMapper.class),
                            sql.getMapper(LedgerMapper.class),
                            sql.getMapper(OutboxMapper.class));
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
                            guard);
            reservations =
                    new ReservationServiceImpl(
                            sql.getMapper(ReservationMapper.class),
                            sql.getMapper(ReservationLineMapper.class),
                            sql.getMapper(BalanceMapper.class),
                            outbound,
                            org.mockito.Mockito.mock(
                                    InboundService.class)); // return-stock is outside this protocol
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

        public void close() {
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
