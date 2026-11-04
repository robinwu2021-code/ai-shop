package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.pay.channel.*;
import ai.neargo.shop.pay.service.PlatformCollectionService;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

class PlatformCollectionMysqlTest {
    @Test
    void providerPrecisionPreservesActualTimeAndRejectsEarlierBuckets() throws Exception {
        for (long paidAt : List.of(9000L, 10000L)) {
            try (var f = new Fixture()) {
                var old = f.request("A");
                var request =
                        new PlatformCollectionService.Request(
                                old.key(),
                                old.buyerNo(),
                                old.merchantNo(),
                                old.channel(),
                                old.method(),
                                old.amountMinor(),
                                old.currency(),
                                old.expiresAt(),
                                10250,
                                old.payer());
                f.service.create(request);
                String external =
                        f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
                f.answer.set(
                        new PayGateway.QueryResult(
                                true,
                                true,
                                true,
                                100,
                                "WX1",
                                "CNY",
                                paidAt,
                                "platform-mch",
                                external,
                                "SUCCESS",
                                1000L));
                var result = f.service.query(request.key());
                assertEquals(paidAt == 10000 ? "SUCCESS" : "UNKNOWN", result.state());
                if (paidAt == 10000) {
                    assertEquals(paidAt, result.paidAt());
                    assertEquals(1000L, result.paidAtPrecisionMs());
                    assertEquals(
                            "PENDING",
                            f.jdbc.queryForObject(
                                    "SELECT platform_handoff_state FROM stl_payment",
                                    String.class));
                    assertEquals(result, f.service.query(request.key()));
                }
            }
        }
        try (var f = new Fixture()) {
            var r = f.request("A");
            f.service.create(r);
            String external =
                    f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "WX1",
                            "CNY",
                            r.preparedAt(),
                            "platform-mch",
                            external,
                            "SUCCESS"));
            var legacy = f.service.query(r.key());
            assertEquals("SUCCESS", legacy.state());
            assertEquals(1L, legacy.paidAtPrecisionMs());
            f.jdbc.update(
                    "UPDATE stl_payment SET"
                        + " platform_receipt_json=JSON_REMOVE(platform_receipt_json,'$.paidAtPrecisionMs')");
            assertEquals(legacy, f.service.query(r.key()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PayGateway.QueryResult(
                                true,
                                true,
                                true,
                                100,
                                "WX1",
                                "CNY",
                                Long.MAX_VALUE,
                                "platform-mch",
                                "M1",
                                "SUCCESS",
                                1000L));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PayGateway.QueryResult(
                                true,
                                true,
                                true,
                                100,
                                "WX1",
                                "CNY",
                                10000L,
                                "platform-mch",
                                "M1",
                                "SUCCESS",
                                -1L));
    }

    @Test
    void oneLedgerPreservesFirstActualProofAndUnknownNeverClosesDispatchedIntent()
            throws Exception {
        try (var f = new Fixture()) {
            var pending = assertDoesNotThrow(() -> f.service.create(f.request("A")));
            assertEquals("PENDING", pending.state());
            assertEquals(Map.of("package", "prepay_id=x"), pending.checkoutParameters());
            assertEquals(1, f.sends.get());
            assertEquals(
                    "PLATFORM_MALL",
                    f.jdbc.queryForObject("SELECT pay_scene FROM stl_payment", String.class));
            f.answer.set(PayGateway.QueryResult.notFound());
            assertEquals("UNKNOWN", f.service.close(f.request("A").key(), f.request("A")).state());
            assertNull(
                    f.jdbc.queryForObject(
                            "SELECT platform_close_fence FROM stl_payment", String.class));
            f.service.create(f.request("A"));
            assertEquals(1, f.sends.get());
            String external =
                    f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "WX1",
                            "CNY",
                            10000L,
                            "platform-mch",
                            external,
                            "SUCCESS"));
            var paid = f.service.query(f.request("A").key());
            assertEquals("SUCCESS", paid.state());
            assertEquals(10000L, paid.paidAt());
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "CHANGED",
                            "CNY",
                            20000L,
                            "platform-mch",
                            external,
                            "SUCCESS"));
            assertEquals(paid, f.service.query(f.request("A").key()));
            var changed =
                    new PlatformCollectionService.Request(
                            f.request("A").key(),
                            "buyer",
                            "wrong",
                            "WECHAT",
                            "JSAPI",
                            100,
                            "CNY",
                            20000,
                            1000,
                            f.request("A").payer());
            assertThrows(RuntimeException.class, () -> f.service.create(changed));
            assertEquals(
                    1, f.jdbc.queryForObject("SELECT count(*) FROM stl_payment", Integer.class));
        }
    }

    @Test
    void closeBeforeFirstCreateAndPausedDispatchCannotBeBypassed() throws Exception {
        try (var f = new Fixture();
                var pool = Executors.newSingleThreadExecutor()) {
            var command = f.request("closed");
            var closed = assertDoesNotThrow(() -> f.service.close(command.key(), command));
            assertEquals("CLOSED", closed.state());
            assertNotNull(closed.closureReference());
            assertEquals(closed, f.service.create(command));
            assertEquals(0, f.sends.get());
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            f.entered = entered;
            f.resume = resume;
            var delayed = pool.submit(() -> f.service.create(f.request("race")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            f.answer.set(PayGateway.QueryResult.notFound());
            assertEquals(
                    "UNKNOWN", f.service.close(f.request("race").key(), f.request("race")).state());
            resume.countDown();
            delayed.get(10, TimeUnit.SECONDS);
            assertEquals(1, f.sends.get());
            assertNull(
                    f.jdbc.queryForObject(
                            "SELECT platform_close_fence FROM stl_payment WHERE status<>'CLOSED'",
                            String.class));
            assertEquals(
                    "UNKNOWN",
                    f.service
                            .close(
                                    new PlatformCollectionService.Key(
                                            "MAIN", "order", "legacy-missing"),
                                    null)
                            .state());
        }
    }

    @Test
    void verifiedLateCashAfterProviderClosureIsDurableAndCannotReplaceFirstMoneyProof()
            throws Exception {
        try (var f = new Fixture()) {
            var request = f.request("late");
            f.service.create(request);
            String external =
                    f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            false,
                            true,
                            0,
                            null,
                            null,
                            null,
                            "platform-mch",
                            external,
                            "CLOSED"));
            assertEquals("CLOSED", f.service.close(request.key(), request).state());
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "WX-LATE",
                            "CNY",
                            10000L,
                            "other-receiver",
                            external,
                            "SUCCESS"));
            assertEquals("CLOSED", f.service.query(request.key()).state());
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "WX-LATE",
                            "CNY",
                            10000L,
                            "platform-mch",
                            external,
                            "SUCCESS"));
            assertEquals("SUCCESS", f.service.query(request.key()).state());
            assertEquals(
                    "PENDING",
                    f.jdbc.queryForObject(
                            "SELECT platform_handoff_state FROM stl_payment", String.class));
            assertEquals(1, f.sends.get());
        }
    }

    static final class Fixture implements AutoCloseable {
        final String schema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin, jdbc;
        final javax.sql.DataSource ds;
        final AtomicInteger sends = new AtomicInteger();
        final AtomicReference<PayGateway.QueryResult> answer =
                new AtomicReference<>(PayGateway.QueryResult.unpaid());
        volatile CountDownLatch entered, resume;
        final PlatformCollectionService service;
        final PayGateway gateway;
        final PayGatewayRouter router;

        Fixture() throws Exception {
            String url = System.getenv("COMMERCE_MYSQL_URL"),
                    user = System.getenv("COMMERCE_MYSQL_USER"),
                    pass = System.getenv("COMMERCE_MYSQL_PASSWORD");
            assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"));
            admin = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));
            admin.execute("CREATE DATABASE " + schema);
            ds = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), user, pass);
            jdbc = new JdbcTemplate(ds);
            var ddl =
                    new ClassPathResource("schema-test.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
            var m =
                    Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS stl_payment\\s*\\(.*?\n\\);")
                            .matcher(ddl);
            assertTrue(m.find());
            jdbc.execute(m.group());
            gateway = org.mockito.Mockito.mock(PayGateway.class);
            org.mockito.Mockito.when(gateway.payChannel()).thenReturn("WECHAT");
            org.mockito.Mockito.when(gateway.platformCollectionCapability())
                    .thenReturn(
                            Optional.of(
                                    new PayGateway.PlatformCollectionCapability(
                                            "platform-mch",
                                            "wx-app",
                                            "JSAPI",
                                            "CNY",
                                            Integer.MAX_VALUE)));
            org.mockito.Mockito.when(gateway.prepay(org.mockito.Mockito.any()))
                    .thenAnswer(
                            invocation -> {
                                assertFalse(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                var command = (PayGateway.PrepayCommand) invocation.getArgument(0);
                                assertNotNull(
                                        jdbc.queryForObject(
                                                "SELECT platform_dispatch_at FROM stl_payment WHERE"
                                                    + " out_trade_no=?",
                                                Long.class,
                                                command.outTradeNo()));
                                sends.incrementAndGet();
                                if (entered != null) {
                                    entered.countDown();
                                    assertTrue(resume.await(10, TimeUnit.SECONDS));
                                }
                                return PayGateway.PrepayResult.ok(
                                        Map.of("package", "prepay_id=x"), null);
                            });
            org.mockito.Mockito.when(gateway.query(org.mockito.Mockito.anyString()))
                    .thenAnswer(
                            i -> {
                                assertFalse(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return answer.get();
                            });
            org.mockito.Mockito.when(gateway.close(org.mockito.Mockito.anyString()))
                    .thenAnswer(
                            i -> {
                                assertFalse(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return answer.get();
                            });
            router = new PayGatewayRouter(List.of(gateway));
            service =
                    new PlatformCollectionServiceImpl(
                            ds, new DataSourceTransactionManager(ds), router);
        }

        PlatformCollectionService.Request request(String attempt) {
            return new PlatformCollectionService.Request(
                    new PlatformCollectionService.Key("MAIN", "order", attempt),
                    "buyer",
                    "platform-mch",
                    "WECHAT",
                    "JSAPI",
                    100,
                    "CNY",
                    20000,
                    1000,
                    new PlatformCollectionService.Payer(
                            "MERCHANT", "USR", "raw-user", "wx-app", "verified-openid"));
        }

        public void close() {
            if (resume != null) resume.countDown();
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
