package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.RefundBasisMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper;
import ai.neargo.shop.pay.settle.refund.MybatisRefundBasisStore;
import ai.neargo.shop.pay.settle.refund.RefundBasisHistory;
import ai.neargo.shop.pay.settle.refund.RefundBasisStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 只接受调用方提供的独立临时库；实际MyBatis Store在MariaDB RR下重放。 */
@EnabledIfSystemProperty(named = "order.basis.mariadb.url", matches = "jdbc:mysql:.*")
class RefundBasisMariaDbReplayTest {
    @Test
    void concurrentRepeatableReadBackfillsCommitOneVersionAndPreserveOtherBill() throws Exception {
        Fixture f = fixture();
        var jdbc = f.jdbc();
        var bills = f.bills();
        var store = f.store();
        var tx = f.tx();
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        CyclicBarrier snapshots = new CyclicBarrier(2);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                tx.executeWithoutResult(status -> replay(jdbc, store, bills, snapshots, null));
                firstCommitted.countDown();
            });
            var second = pool.submit(() -> tx.executeWithoutResult(status -> {
                replay(jdbc, store, bills, snapshots, firstCommitted);
                insertBill(jdbc, "OTHER");
            }));
            assertThatCode(() -> first.get(20, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThatCode(() -> second.get(20, TimeUnit.SECONDS)).doesNotThrowAnyException();
        }
        assertThat(jdbc.queryForObject("select count(*) from stl_bill_refund_basis", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from stl_bill where sub_order_no='OTHER'", Long.class))
                .isEqualTo(1L);
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            insertBill(jdbc, "NEW");
            var fresh = bills.selectOne(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, "NEW"));
            store.recordCreated(fresh, 300);
            store.backfill(fresh, 300);
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from stl_bill_refund_basis", Long.class)).isEqualTo(2L);
    }

    record Fixture(JdbcTemplate jdbc, BillMapper bills, RefundBasisStore store, TransactionTemplate tx,
                   SqlSessionTemplate session) { }

    static Fixture fixture() throws Exception {
        String url = System.getProperty("order.basis.mariadb.url");
        assertThat(url).matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/codex_order_f06_[a-f0-9]+\\?.*");
        var ds = new DriverManagerDataSource(url,
                System.getenv("ORDER_BASIS_DB_USER"), System.getenv("ORDER_BASIS_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        insertBill(jdbc, "LEGACY");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(RefundBasisMapper.class, BillMapper.class,
                PaymentMapper.class, BillRefundMapper.class, SplitLogMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        factory.setGlobalConfig(global);
        var session = new SqlSessionTemplate(factory.getObject());
        var bills = session.getMapper(BillMapper.class);
        var target = new MybatisRefundBasisStore(session.getMapper(RefundBasisMapper.class), bills,
                session.getMapper(PaymentMapper.class), new RefundBasisHistory(
                session.getMapper(BillRefundMapper.class),
                session.getMapper(PaymentMapper.class), session.getMapper(SplitLogMapper.class)));
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(ds));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(advice);
        var store = (RefundBasisStore) proxy.getProxy();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return new Fixture(jdbc, bills, store, tx, session);
    }

    private static void replay(JdbcTemplate jdbc, RefundBasisStore store, BillMapper bills,
                               CyclicBarrier snapshots, CountDownLatch firstCommitted) {
        try {
            // 两个事务先同时建立“无Basis”的旧快照，第二个再等第一笔已经提交。
            assertThat(jdbc.queryForObject("select count(*) from stl_bill_refund_basis", Long.class)).isZero();
            var bill = bills.selectOne(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, "LEGACY"));
            snapshots.await(10, TimeUnit.SECONDS);
            if (firstCommitted != null) assertThat(firstCommitted.await(15, TimeUnit.SECONDS)).isTrue();
            store.backfill(bill, 300);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void schema(JdbcTemplate jdbc) throws Exception {
        String schema = Files.readString(Path.of("src/test/resources/schema-test.sql"));
        for (String table : List.of("stl_bill", "stl_bill_refund", "stl_payment",
                "stl_bill_refund_basis", "stl_split_log")) {
            var ddl = Pattern.compile("CREATE TABLE IF NOT EXISTS " + table + "\\s*\\([\\s\\S]*?\\n\\);")
                    .matcher(schema);
            assertThat(ddl.find()).as(table).isTrue();
            jdbc.execute(ddl.group());
        }
    }

    private static void insertBill(JdbcTemplate jdbc, String no) {
        jdbc.update("insert into stl_bill(settle_no,sub_order_no,order_no,entity_no,gross_minor,commission_minor,"
                + "net_minor,pay_channel,pay_merchant_no,tenant_no,created_at,updated_at)"
                + " values(?,?,?,'M1',300,30,270,'OFFLINE','OLD','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                no, no, no);
    }
}
