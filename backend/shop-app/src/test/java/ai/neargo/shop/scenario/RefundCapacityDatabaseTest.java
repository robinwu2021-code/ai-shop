package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.config.PayDataSourceConfig;
import ai.neargo.shop.pay.service.impl.*;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundRecoveryStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@SpringJUnitConfig(RefundCapacityDatabaseTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={
    "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:capacity-native;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
    "spring.datasource.username=${points.jdbc.user:sa}","spring.datasource.password=${points.jdbc.password:}",
    "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"})
class RefundCapacityDatabaseTest {
    @Autowired RefundPaymentStore refunds;
    @Autowired RefundCapacityLedger capacity;
    @Autowired ai.neargo.shop.pay.service.PaymentLedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.neargo.shop.pay.points.store.PointsCashRefundReadStore cash;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("payTxManager")
    org.springframework.transaction.PlatformTransactionManager payTx;

    @BeforeAll void schema() throws Exception {
        String schema=new ClassPathResource("schema-test.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var match=Pattern.compile("CREATE TABLE IF NOT EXISTS stl_payment\\b.*?;",Pattern.DOTALL).matcher(schema);
        assertThat(match.find()).isTrue(); jdbc.execute(match.group());
    }
    @Test void twoCashPathsSerializeAgainstTheSameOriginalReceipt() throws Exception {
        String origin=origin(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { start.await(); try { refunds.prepare(request(origin,"A")); return true; }
                catch(ai.neargo.shop.common.BizException full) { return false; } });
            var b=pool.submit(() -> { start.await(); try { ledger.refund(origin,"S:"+origin,"B:"+origin,100,"refund"); return true; }
                catch(ai.neargo.shop.common.BizException full) { return false; } });
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select sum(amount_minor) from stl_payment where order_no=? and direction='REFUND'",Long.class,origin))
                .isEqualTo(100);
    }
    @Test void safeReleaseKeepsTheRecoveryEvidenceAndAllowsAnotherAttempt() {
        String origin=origin(); var first=refunds.prepare(request(origin,"A"));
        refunds.reject(first.getRefundExecutionNo(),"verified rejection");
        capacity.releaseAfterRecovery(proof(origin,first.getPaymentNo()));
        assertThat(jdbc.queryForObject("select refund_recovery_proof from stl_payment where payment_no=?",String.class,first.getPaymentNo()))
                .contains("ABORTED");
        assertThat(ledger.refund(origin,"S:"+origin,"B:"+origin,100,"retry")).isNotBlank();
    }
    @Test void contradictorySuccessFreezesOriginalAndRetainsTheActualReceipt() {
        String origin=origin(); var first=refunds.prepare(request(origin,"A"));
        refunds.reject(first.getRefundExecutionNo(),"verified rejection");
        capacity.releaseAfterRecovery(proof(origin,first.getPaymentNo()));
        String reserved=ledger.refund(origin,"S:"+origin,"B:"+origin,100,"retry");
        refunds.confirm(first.getOutTradeNo(),"TEST",100,"CNY","LATE:"+origin);
        assertThat(ledger.claimRefundDispatch(reserved)).isFalse();
        assertThat(jdbc.queryForObject("select refund_terminal_proof from stl_payment where payment_no=?",String.class,first.getPaymentNo()))
                .contains("LATE:"+origin);
        assertThatThrownBy(() -> ledger.refund(origin,"S:"+origin,"C:"+origin,100,"retry"))
                .hasMessage("REFUND_ORIGIN_FROZEN");
    }
    @Test void safetyLockSeesLateSuccessAfterAnOlderRepeatableReadSnapshot() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            var metadata=connection.getMetaData();
            org.junit.jupiter.api.Assumptions.assumeTrue(metadata.getDatabaseProductName().contains("MySQL")
                    && !metadata.getDatabaseProductVersion().contains("MariaDB"),
                    "MySQL RR current read; H2/MariaDB reject a changed row with a serialization error");
        }
        assertCurrentSafety(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Test void safetyLockSeesConcurrentLateSuccessUnderReadCommitted() throws Exception {
        assertCurrentSafety(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private void assertCurrentSafety(int isolation) throws Exception {
        String origin=origin();var first=refunds.prepare(request(origin,"A"));
        refunds.reject(first.getRefundExecutionNo(),"verified rejection");
        var tx=new org.springframework.transaction.support.TransactionTemplate(payTx);
        tx.setIsolationLevel(isolation);
        try(var pool=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                assertThat(cash.refund(first.getPaymentNo()).safety().safeToRecover()).isTrue();
                try { pool.submit(() -> refunds.confirm(first.getOutTradeNo(),"TEST",100,"CNY","LATE:"+origin))
                        .get(10,TimeUnit.SECONDS); }
                catch(Exception failure) { throw new RuntimeException(failure); }
                var current=cash.lockRefund(first.getPaymentNo());
                assertThat(current.safety().outcome().name()).isEqualTo("MANUAL_REQUIRED");
                assertThat(current.safety().originFrozen()).isTrue();
                assertThat(current.safety().safeToRecover()).isFalse();
            });
        }
    }

    private String origin() {
        String no="N"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,user_no,amount_minor,currency,status,pay_channel,trade_no,out_trade_no,created_at,updated_at) values(?,'PAY',?,'U',100,'CNY','SUCCESS','TEST',?,?,NOW(),NOW())",no,no,no,no);
        return no;
    }
    private RefundRequest request(String origin,String suffix) {
        return new RefundRequest("E:"+origin+suffix,"A:"+origin+suffix,"S:"+origin,origin,100,"CNY","refund");
    }
    private RefundRecoveryStatus proof(String origin,String payment) {
        return new RefundRecoveryStatus("PREP","A:"+origin+"A","S:"+origin,"ABORTED",100,payment,false,false);
    }
    @Configuration @EnableTransactionManagement
    @Import({PayDataSourceConfig.class,RefundPaymentStore.class,RefundCapacityLedger.class,PaymentLedgerServiceImpl.class,ai.neargo.shop.pay.points.store.impl.MybatisPointsCashRefundReadStore.class})
    static class Config {
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean MybatisPlusInterceptor interceptor() { return new MybatisPlusInterceptor(); }
        @Bean MetaObjectHandler metaObjectHandler() {
            return new MetaObjectHandler() {
                public void insertFill(MetaObject meta) {
                    strictInsertFill(meta,"createdAt",LocalDateTime.class,LocalDateTime.now());
                    strictInsertFill(meta,"updatedAt",LocalDateTime.class,LocalDateTime.now());
                }
                public void updateFill(MetaObject meta) { strictUpdateFill(meta,"updatedAt",LocalDateTime.class,LocalDateTime.now()); }
            };
        }
    }
}
