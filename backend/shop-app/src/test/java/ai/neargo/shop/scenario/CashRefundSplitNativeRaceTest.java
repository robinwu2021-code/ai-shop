package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.mapper.*;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.settle.refund.*;
import ai.neargo.shop.pay.settle.refund.impl.CashRefundSplitServiceImpl;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.spi.points.PointsRefundPort;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(CashRefundSplitNativeRaceTest.Config.class)
class CashRefundSplitNativeRaceTest {
    @Autowired DataSource data;
    @Autowired JdbcTemplate jdbc;
    @Autowired CashRefundSplitService reversals;
    @Autowired SplitGateway bank;
    @BeforeEach void schema() throws Exception {
        try(var c=data.getConnection()) { Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().contains("MySQL")); }
        String sql=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("stl_bill","stl_split_log","stl_bill_refund","stl_split_intent","stl_cash_refund_reverse")) {
            int start=sql.indexOf("CREATE TABLE IF NOT EXISTS "+table+"\n");
            assertThat(start).isGreaterThanOrEqualTo(0);
            jdbc.execute(sql.substring(start,sql.indexOf(';',start)));
        }
    }
    @Test void concurrent_continuation_queries_without_holding_the_bill_lock_or_resubmitting() throws Exception {
        String sub="NCR"+UUID.randomUUID().toString().replace("-","");
        String as="AS-"+sub;
        jdbc.update("INSERT INTO stl_bill(settle_no,sub_order_no,order_no,entity_no,store_no,pay_merchant_no,pay_channel,currency,"
                +"status,gross_minor,commission_minor,net_minor,split_amount_minor,funds_mode,split_at,created_at,updated_at)"
                +" VALUES(?,?,?,'M','STORE','ORIGINAL','TEST','CNY','SPLIT',9000,540,8460,540,'DIRECT',1,NOW(),NOW())",sub,sub,sub);
        jdbc.update("INSERT INTO stl_split_log(settle_no,sub_order_no,split_action,amount_minor,request_no,result,provider_no,at,created_at)"
                +" VALUES(?,?,'SPLIT',540,?,'SUCCESS',?,1,NOW())",sub,sub,"SPL-"+sub,"BANK-SPL-"+sub);
        var cmd=new SplitReversal(sub,as,3000,0,9000);
        var sending=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(bank.reverse(any(ai.neargo.shop.pay.settle.SplitCommand.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT state FROM stl_cash_refund_reverse WHERE after_sale_no=?",String.class,as)).isEqualTo("DISPATCHING");
            sending.countDown();
            assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            return SplitGateway.Result.ok("NATIVE-"+as);
        });
        when(bank.queryReverse(any(ai.neargo.shop.pay.settle.SplitCommand.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return SplitGateway.Result.fail("ORIGINAL_STILL_PENDING");
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> reversals.reverse(cmd));
            if(!sending.await(3,TimeUnit.SECONDS)) first.get(1,TimeUnit.SECONDS);
            var second=pool.submit(() -> reversals.reverse(cmd));
            try { assertThat(second.get(2,TimeUnit.SECONDS)).isFalse(); }
            finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(reversals.reverse(cmd)).isTrue();
        verify(bank,times(1)).reverse(any(ai.neargo.shop.pay.settle.SplitCommand.class));
        verify(bank,times(1)).queryReverse(any(ai.neargo.shop.pay.settle.SplitCommand.class));
        assertThat(jdbc.queryForObject("SELECT reversed_minor FROM stl_bill WHERE sub_order_no=?",Long.class,sub)).isEqualTo(180);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_bill_refund WHERE after_sale_no=?",Long.class,as)).isEqualTo(1);
    }
    @Configuration(proxyBeanMethods=false)
    @MapperScan("ai.neargo.shop.pay.mapper")
    @Import({CashRefundSplitServiceImpl.class,DefaultSettlementRefundPolicy.class,BillRefundJournal.class})
    static class Config {
        @Bean DataSource dataSource(Environment env) {
            var ds=new DriverManagerDataSource();
            String url=env.getProperty("points.source.jdbc.url","jdbc:h2:mem:cash_reverse_native;MODE=MySQL");
            ds.setDriverClassName(url.startsWith("jdbc:mysql:")?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
            ds.setUrl(url); ds.setUsername(env.getProperty("points.source.jdbc.user","sa"));
            ds.setPassword(env.getProperty("points.source.jdbc.password","")); return ds;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource data) throws Exception {
            var f=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean(); f.setDataSource(data);
            return f.getObject();
        }
        @Bean(name="payTxManager") DataSourceTransactionManager payTx(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean JdbcTemplate jdbc(DataSource data) { return new JdbcTemplate(data); }
        @Bean SplitGateway bank() { return mock(SplitGateway.class); }
        @Bean PointsSettlementRefundHoldMapper holds() { return mock(PointsSettlementRefundHoldMapper.class); }
        @Bean SettlementRefundCoordinator coordinator(BillMapper bills,BillRefundJournal journal,SettlementRefundPolicy policy,
                PointsSettlementRefundHoldMapper holds,CashRefundSplitMapper intents) {
            return new SettlementRefundCoordinator(bills,journal,policy,mock(PreSplitAdjuster.class),mock(PostSplitReverser.class),
                    holds,mock(PointsRefundPort.class),intents, mock(ai.neargo.shop.pay.service.impl.RefundCapacityLedger.class));
        }
    }
}
