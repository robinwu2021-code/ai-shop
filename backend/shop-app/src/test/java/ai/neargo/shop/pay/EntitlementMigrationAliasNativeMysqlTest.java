package ai.neargo.shop.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationAliasMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntCommandDedupMapper;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationAliasWriter;
import ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator;
import ai.neargo.shop.pay.migration.service.LegacyMigrationEvidenceReader;
import ai.neargo.shop.pay.migration.service.LegacySnapshotCipher;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Production writer/mapper concurrency only; authenticated source comparison has separate real two-JVM acceptance. */
@EnabledIfEnvironmentVariable(named="ALIAS_NATIVE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class EntitlementMigrationAliasNativeMysqlTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions {}
    @Test void recognitionIsUniqueAndStaleRepeatableReadCannotRetainAPendingCommand() throws Exception {
        String url=System.getenv("ALIAS_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/alias_native_fixture\\?.*");
        var ds=new DriverManagerDataSource(url,"root",""); var jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V425__entitlement_catalog.sql"),
                new ClassPathResource("db/migration/V462__entitlement_migration_evidence_stage.sql"),
                new ClassPathResource("db/migration/V463__entitlement_manifest_stage_metadata.sql"),
                new ClassPathResource("db/migration/V464__entitlement_migration_alias.sql")).execute(ds);
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        SqlSessionFactory sessions=factory.getObject();
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class,EntitlementCommandDeduplicator.class,EntitlementMigrationAliasWriter.class,
                    LegacyMigrationEvidenceReader.class);
            context.registerBean("payTxManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
            context.registerBean(ObjectMapper.class,()->new ObjectMapper());
            // The local writer only uses AAD binding and must not decrypt or fetch a remote source.
            context.registerBean(LegacySnapshotCipher.class,()->new LegacySnapshotCipher("",""));
            register(context,EntMigrationStageMapper.class,sessions);
            register(context,EntMigrationAliasMapper.class,sessions);
            register(context,PayEntCommandDedupMapper.class,sessions);
            context.refresh();
            var stages=context.getBean(EntMigrationStageMapper.class);
            var writer=context.getBean(EntitlementMigrationAliasWriter.class);
            var pmt=stage(stages,"P1","PMT","T1","B1");
            var mkt=stage(stages,"M1","MKT_LEGACY","T1","B1");
            var scope=new CatalogScope("MAIN","M1","NATIVE",true);
            var command=new MigrationAliasViews.Recognize(new MigrationAliasViews.Pair("P1","M1"),0,0,"native writer only");
            var comparison=new LegacyCouponAliasComparator.Comparison("CANDIDATE_ALIAS",false,1,"e".repeat(64),List.of());
            Instant at=Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var attempts=new ArrayList<Callable<MigrationAliasViews.AliasView>>();
            for(int i=0;i<20;i++) attempts.add(()->writer.recognize(scope,command,"SAME",pmt,mkt,comparison,at));
            try(var pool=Executors.newFixedThreadPool(6)) {
                var results=pool.invokeAll(attempts,20,TimeUnit.SECONDS);
                var first=results.getFirst().get();
                for(var result:results) assertThat(result.get()).isEqualTo(first);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_alias",Long.class)).isEqualTo(1);
            assertThatThrownBy(()->writer.recognize(scope,command,"NEW-KEY",pmt,mkt,comparison,at))
                    .isInstanceOf(BizException.class);
            var pmt2=stage(stages,"P2","PMT","T2","B2");
            var mkt2=stage(stages,"M2","MKT_LEGACY","T2","B2");
            var second=new MigrationAliasViews.Recognize(new MigrationAliasViews.Pair("P2","M2"),0,0,"second native pair");
            var tx=new TransactionTemplate(context.getBean("payTxManager",PlatformTransactionManager.class));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try(var other=Executors.newSingleThreadExecutor()) {
                assertThatThrownBy(()->tx.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_alias",Long.class)).isEqualTo(1);
                    try { other.submit(()->writer.recognize(scope,second,"OTHER-TX",pmt2,mkt2,comparison,at))
                            .get(10,TimeUnit.SECONDS); }
                    catch(Exception failure) { throw new AssertionError(failure); }
                    // The original consistent snapshot is stale, but the unique insert must reject duplication.
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_alias",Long.class)).isEqualTo(1);
                    writer.recognize(scope,second,"STALE-SNAPSHOT",pmt2,mkt2,comparison,at);
                })).isInstanceOf(BizException.class);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_alias",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE result_status='PENDING'",
                    Long.class)).isZero();
        }
    }
    private static EntMigrationStage stage(EntMigrationStageMapper stages,String no,String source,String template,String batch) {
        var row=new EntMigrationStage(); EntitlementRecords.audit(row,new EntitlementContext("MAIN","NATIVE",no,"a".repeat(64)));
        row.setStageNo(no); row.setSourceSystem(source); row.setSourceTemplateNo(template); row.setMigrationBatchNo(batch);
        row.setEntityNo("M1"); row.setRequestKey("STAGE-"+no); row.setCommandHash("a".repeat(64)); row.setReason("native binding fixture");
        row.setSourceSchemaVersion(1); row.setFrozenRevision(2L); row.setWatermark("a".repeat(64));
        row.setContentHash(("PMT".equals(source)?"b":"c").repeat(64)); row.setCryptoKeyId("TEST");
        row.setPayloadCipher("opaque-fixture"); row.setPayloadBytes(1); row.setInstanceCount(1L); row.setHistoryCount(0L);
        row.setManifestVersion(2); row.setHistoryAvailable("PMT".equals(source)); row.setStatus("STAGED");
        stages.claim(row); return stages.selectById(row.getId()==null
                ?stages.lockRequest("MAIN",row.getRequestKey()).getId():row.getId());
    }
    private static <T> void register(AnnotationConfigApplicationContext context,Class<T> type,SqlSessionFactory sessions) {
        context.registerBean(type.getName(),MapperFactoryBean.class,()->{
            var bean=new MapperFactoryBean<T>(type); bean.setSqlSessionFactory(sessions); return bean;
        });
    }
}
