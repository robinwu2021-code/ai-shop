package ai.neargo.shop.pay;
import ai.neargo.shop.pay.migration.service.impl.LegacyCouponUseServiceImpl;


import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementPostingServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.*;
import ai.neargo.shop.pay.migration.service.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Production pay service/writer/ledger on MySQL; only authoritative core facts and routing are fixtures. */
@EnabledIfEnvironmentVariable(named="LEGACY_USE_NATIVE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class LegacyCouponUseNativeMysqlTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions {}
    static final class Facts implements LegacyCouponCheckoutSourcePort {
        final Map<String,CheckoutFact> facts=new ConcurrentHashMap<>();
        final Map<String,Decision> decisions=new ConcurrentHashMap<>();
        final Map<String,Reversal> reversals=new ConcurrentHashMap<>();
        private void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
        public CheckoutFact resolve(EntitlementContext ctx,String no){outside();return facts.get(no);}
        public Optional<Decision> decision(EntitlementContext ctx,String no){outside();return Optional.ofNullable(decisions.get(no));}
        public Optional<Reversal> reversal(EntitlementContext ctx,String no){outside();return Optional.ofNullable(reversals.get(no));}
    }
    @Test void realJsonConcurrentHoldsAndTerminalDecisionsKeepOneAssetLedger() throws Exception {
        String url=System.getenv("LEGACY_USE_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/legacy_use_native_fixture\\?.*");
        var ds=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V425__entitlement_catalog.sql"),
                new ClassPathResource("db/migration/V434__entitlement_grants_and_ledger.sql"),
                new ClassPathResource("db/migration/V465__legacy_coupon_pay_operation.sql"),
                new ClassPathResource("db/migration/V429__pay_entitlement_event_outbox_inbox.sql"),
                new ClassPathResource("db/migration/V436__entitlement_use_and_daily_quota.sql"),
                new ClassPathResource("db/migration/V468__legacy_coupon_usage_facts.sql"),
                new ClassPathResource("db/migration/V471__legacy_coupon_target_import.sql"),
                new ClassPathResource("db/migration/V472__legacy_coupon_historical_usage.sql")).execute(ds);
        var cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);
        var plugins=new MybatisPlusInterceptor();plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);factory.setPlugins(plugins);
        SqlSessionFactory sessions=factory.getObject();
        var source=new Facts();var props=new PayEntitlementProperties();
        props.getEntitlement().getUse().setEnabled(true);props.getEntitlement().getLegacyCoupon().setEnabled(true);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class,ai.neargo.shop.pay.migration.service.LegacyCouponSnapshotReader.class,LegacyCouponUseServiceImpl.class,LegacyCouponUseWriter.class,LegacyCouponUsageRecorder.class,
                    ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher.class,
                    EntitlementPostingServiceImpl.class,PayEntitlementFeatureGate.class);
            context.registerBean("payTxManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
            context.registerBean(ObjectMapper.class,()->new ObjectMapper());
            context.registerBean(PayEntitlementProperties.class,()->props);
            context.registerBean(LegacyCouponCheckoutSourcePort.class,()->source);
            context.registerBean(EntitlementOwnerRegistryPort.class,()->(ctx,origin)->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new OwnerRef(origin,"I1",OwnerSystem.PAY,MigrationState.CUTOVER,"B1","b".repeat(64),0);
            });
            register(context,EntInstanceMapper.class,sessions);register(context,EntLedgerMapper.class,sessions);
            register(context,EntLegacyCouponOperationMapper.class,sessions);register(context,EntUsageMapper.class,sessions);
            register(context,PayEntOutboxMapper.class,sessions);context.refresh();
            var json=context.getBean(ObjectMapper.class);var uses=context.getBean(LegacyCouponUseService.class);
            Instant from=Instant.now().minusSeconds(100),until=Instant.now().plusSeconds(3600);
            var rule=new EntitlementDefinition("old","ORDER_COUPON","MERCHANT","CNY","CASH",100L,null,null,300L,null,
                    "COUNT",2,null,new Validity("ABSOLUTE",from,until,null,"GRANTED","ROLLING_DAYS","Asia/Shanghai"),
                    new RedemptionScope("ALL",List.of()),List.of("TARGETED"));
            jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,entity_no,kind,"
                    +"status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,effective_from,effective_until,"
                    +"tenant_no,created_at,updated_at,created_by,updated_by) VALUES('I1','V1','G1','SRC1','U1','M1','ORDER_COUPON',"
                    +"'ACTIVE',?,1,2,2,0,1,?,?,'MAIN',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')",json.writeValueAsString(rule),
                    LocalDateTime.ofInstant(from,ZoneOffset.UTC),LocalDateTime.ofInstant(until,ZoneOffset.UTC));
            jdbc.update("INSERT INTO ent_ledger(entry_no,instance_no,asset_seq,biz_type,biz_key,units_delta,reserved_delta,after_units,"
                    +"after_reserved,occurred_at,tenant_no,created_at,updated_at,created_by,updated_by) VALUES('E1','I1',1,'GRANT',"
                    +"'GRANT:SEED',2,0,2,0,UTC_TIMESTAMP(3),'MAIN',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')");
            var ctx=new EntitlementContext("MAIN","NATIVE","NATIVE","a".repeat(64));
            for(String no:List.of("C1","C2","C3","C4")) source.facts.put(no,new CheckoutFact(1,IntentKind.CHECKOUT,"MAIN",no,"OP"+no,
                    "U1","M1",new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"OLD1"),"I1","B1","b".repeat(64),"CNY",
                    List.of(new MerchantGroup("S1","M1","S1",101),new MerchantGroup("S2","M1","S2",199)),
                    "c".repeat(64),"NATIVE",from,until));
            var same=new Prepare("C1","OPC1","U1","M1");
            var attempts=new ArrayList<Callable<Operation>>();
            for(int i=0;i<20;i++) attempts.add(()->uses.prepare(ctx,same));
            try(var pool=Executors.newFixedThreadPool(6)) {
                for(var result:pool.invokeAll(attempts,20,TimeUnit.SECONDS))
                    assertThat(result.get().status()).isEqualTo(State.HELD);
            }
            var go=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var a=pool.submit(()->{go.await();try{uses.prepare(ctx,new Prepare("C2","OPC2","U1","M1"));return true;}
                    catch(BizException expected){return false;}});
                var b=pool.submit(()->{go.await();try{uses.prepare(ctx,new Prepare("C3","OPC3","U1","M1"));return true;}
                    catch(BizException expected){return false;}});
                go.countDown();
                assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_legacy_coupon_operation",Integer.class)).isEqualTo(2);
            props.getEntitlement().getLegacyCoupon().setEnabled(false);
            props.getEntitlement().getUse().setEnabled(false);
            source.decisions.put("C1",new Decision("C1","OPC1","D1",Outcome.COMMIT,"O1","c".repeat(64),100L,Instant.now()));
            uses.commit(ctx,new Complete("C1","OPC1","M1","D1"));uses.commit(ctx,new Complete("C1","OPC1","M1","D1"));
            source.reversals.put("C1",new Reversal("C1","OPC1","R1","O1","REFUND1","c".repeat(64),"CNY",100,
                    ReverseReason.FULL_REFUND,Instant.now()));
            uses.reverse(ctx,new Complete("C1","OPC1","M1","R1"));uses.reverse(ctx,new Complete("C1","OPC1","M1","R1"));
            String other=jdbc.queryForObject("SELECT checkout_no FROM ent_legacy_coupon_operation WHERE status='HELD'",String.class);
            source.decisions.put(other,new Decision(other,"OP"+other,"A2",Outcome.ABORT,null,"c".repeat(64),null,Instant.now()));
            uses.release(ctx,new Complete(other,"OP"+other,"M1","A2"));
            source.decisions.put("C4",new Decision("C4","OPC4","A4",Outcome.ABORT,null,"c".repeat(64),null,Instant.now()));
            assertThat(uses.release(ctx,new Complete("C4","OPC4","M1","A4")).discountMinor()).isNull();
            assertThat(uses.prepare(ctx,new Prepare("C4","OPC4","U1","M1")).status()).isEqualTo(State.RELEASED);
            assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no='I1'",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no='I1'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no='I1'",Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_legacy_coupon_operation WHERE status='PREPARING'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM ent_usage",String.class)).isEqualTo("REVERSED");
            assertThat(jdbc.queryForObject("SELECT store_no FROM ent_usage",String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT amount_minor FROM ent_usage",Long.class)).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox",Integer.class)).isEqualTo(2);
            var history=context.getBean(EntUsageMapper.class).history("MAIN","U1","M1","I1",null,null,2);
            assertThat(history).hasSize(1);assertThat(history.getFirst().getStatus()).isEqualTo("REVERSED");
            assertThat(context.getBean(EntUsageMapper.class).history("MAIN","OTHER","M1","I1",null,null,2)).isEmpty();
            jdbc.update("DELETE FROM ent_usage");
            uses.commit(ctx,new Complete("C1","OPC1","M1","D1"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE status='REVERSED'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no='I1'",Integer.class)).isEqualTo(6);
        }
    }
    private static <T> void register(AnnotationConfigApplicationContext ctx,Class<T> type,SqlSessionFactory sessions) {
        ctx.registerBean(type.getName(),MapperFactoryBean.class,()->{var bean=new MapperFactoryBean<T>(type);
            bean.setSqlSessionFactory(sessions);return bean;});
    }
}
