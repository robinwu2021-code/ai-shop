package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.impl.OrderPointsRefundCoordinatorImpl;
import ai.neargo.shop.trade.order.refund.model.*;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderRefundNativeRaceTest.Config.class)
class PointsOrderRefundNativeRaceTest {
    @Autowired DataSource data;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired OrderPointsRefundCoordinator refunds;
    @Autowired OrderPointsBindingService bindings;
    @Autowired RefundExecutionService executions;

    @BeforeEach
    void schema() throws Exception {
        try (var connection = data.getConnection()) {
            Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("MySQL"));
        }
        String schema =
                new String(
                        new ClassPathResource("schema-test.sql").getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
        for (String table : List.of("ord_order", "ord_sub_order", "ord_item", "ord_after_sale")) {
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + "\n");
            assertThat(start).isGreaterThanOrEqualTo(0);
            jdbc.execute(schema.substring(start, schema.indexOf(';', start)));
        }
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS ord_points_refund_binding (after_sale_no VARCHAR(64)"
                    + " PRIMARY KEY,sub_order_no VARCHAR(64),phase VARCHAR(32),source_version"
                    + " BIGINT,command_json LONGTEXT,preparation_json LONGTEXT,cash_payment_no"
                    + " VARCHAR(64),version BIGINT DEFAULT 0,created_at DATETIME DEFAULT"
                    + " CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
    }

    @Test
    void same_sub_order_stages_serially_and_second_command_includes_first_reservation()
            throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String root = "R" + suffix, sub = "S" + suffix, first = "A" + suffix, second = "B" + suffix;
        jdbc.update(
                "INSERT INTO"
                    + " ord_order(order_no,user_no,status,currency,pay_amount,points_integrated,created_at,updated_at)"
                    + " VALUES(?,'U','PAID','CNY',90,1,NOW(),NOW())",
                root);
        jdbc.update(
                "INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,"
                        + "status,pay_amount,goods_amount,freight_amount,created_at,updated_at)"
                        + " VALUES(?,?,'U','M','ST','FULFILLING',90,100,0,NOW(),NOW())",
                sub,
                root);
        jdbc.update(
                "INSERT INTO"
                    + " ord_item(sub_order_no,order_no,goods_no,sku_no,qty,paid_share_minor,supply_store_no,created_at,updated_at)"
                    + " VALUES(?,?,'G','SKU',1,90,'ST',NOW(),NOW())",
                sub,
                root);
        long item =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, sub);
        jdbc.update(
                "INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,"
                        + "type,status,reason,created_at,updated_at)"
                        + " VALUES(?,?,?,'U','M','REFUND_ONLY','REFUNDING','OTHER',NOW(),NOW())",
                first,
                sub,
                root);
        jdbc.update(
                "INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,"
                        + "type,status,reason,created_at,updated_at)"
                        + " VALUES(?,?,?,'U','M','REFUND_ONLY','REFUNDING','OTHER',NOW(),NOW())",
                second,
                sub,
                root);
        var route = new PaymentRoute("M", "ST", "PM", "TEST", "SM", "PM:1", "DIRECT");
        var verified =
                new PointsPaymentSnapshotPort.VerifiedPayment(
                        root,
                        "U",
                        "CNY",
                        90,
                        "TEST",
                        "OUT",
                        "TRADE",
                        Instant.ofEpochMilli(1000),
                        List.of(new PaymentStore(sub, route)));
        var original =
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT", "TEST", "M", "ST", "PM:1", "SM", null, null, 90);
        var snapshot =
                new OrderPointsBindingService.Snapshot(
                        100,
                        0,
                        0,
                        1,
                        1,
                        "{}",
                        "{}",
                        original,
                        List.of(new PointsRedeemPort.Item(String.valueOf(item), 100)));
        var admission =
                new OrderPointsBindingService.Admission(
                        "REQ", root, sub, "U", "M", "ST", "TYPE", "CNY", "ONLINE", 0, true, true,
                        snapshot);
        when(bindings.find(sub))
                .thenReturn(
                        new OrderPointsBindingService.Binding(
                                "B", admission, OrderPointsBindingService.Phase.BOUND, 0, "H"));
        var firstPlan = new RefundPlan(sub, List.of(new RefundLine(item, 0, 45)), 0);
        var secondPlan = new RefundPlan(sub, List.of(new RefundLine(item, 0, 25)), 0);
        when(executions.inspect(first))
                .thenReturn(
                        new RefundExecutionService.Execution(
                                new RefundExecutionService.Preparation(
                                        first, sub, "H1", "PREPARED", 45),
                                firstPlan,
                                null));
        when(executions.inspect(second))
                .thenReturn(
                        new RefundExecutionService.Execution(
                                new RefundExecutionService.Preparation(
                                        second, sub, "H2", "PREPARED", 25),
                                secondPlan,
                                null));

        var pool = Executors.newFixedThreadPool(2);
        var staged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> firstWork =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(tx)
                                            .execute(
                                                    s -> {
                                                        refunds.stage(first, firstPlan, verified);
                                                        staged.countDown();
                                                        try {
                                                            release.await(10, TimeUnit.SECONDS);
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new RuntimeException(e);
                                                        }
                                                        return null;
                                                    }));
            if (!staged.await(3, TimeUnit.SECONDS)) firstWork.get(1, TimeUnit.SECONDS);
            Future<OrderPointsRefundCoordinator.Binding> secondWork =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(tx)
                                            .execute(
                                                    s ->
                                                            refunds.stage(
                                                                    second, secondPlan, verified)));
            assertThatThrownBy(() -> secondWork.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            firstWork.get(10, TimeUnit.SECONDS);
            var result = secondWork.get(10, TimeUnit.SECONDS);
            assertThat(result.command().lines())
                    .extracting(PointsRefundPort.RefundLine::cumulativeGoodsMinor)
                    .containsExactly(77L);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackages = {"ai.neargo.shop.trade.mapper", "ai.neargo.shop.trade.order.mapper"})
    @Import(OrderPointsRefundCoordinatorImpl.class)
    static class Config {
        @Bean
        DataSource dataSource(Environment env) {
            var ds = new DriverManagerDataSource();
            String url =
                    env.getProperty(
                            "points.source.jdbc.url",
                            "jdbc:h2:mem:points_refund_native;MODE=MySQL");
            ds.setDriverClassName(
                    url.startsWith("jdbc:mysql:") ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
            ds.setUrl(url);
            ds.setUsername(env.getProperty("points.source.jdbc.user", "sa"));
            ds.setPassword(env.getProperty("points.source.jdbc.password", ""));
            return ds;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory =
                    new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            return factory.getObject();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        OrderPointsBindingService bindings() {
            return mock(OrderPointsBindingService.class);
        }

        @Bean
        RefundExecutionService executions() {
            return mock(RefundExecutionService.class);
        }

        @Bean
        OrderPointsFactService facts() {
            return mock(OrderPointsFactService.class);
        }
    }
}
