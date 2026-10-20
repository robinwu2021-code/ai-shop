package ai.neargo.shop.pay;
import ai.neargo.shop.pay.migration.service.impl.EntitlementMigrationPlanServiceImpl;
import ai.neargo.shop.pay.migration.service.impl.LegacyCouponMigrationImportServiceImpl;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementPostingServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.*;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.pay.migration.service.*;
import ai.neargo.shop.pay.migration.service.impl.EntitlementOwnerRegistryServiceImpl;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort.CommitProof;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real pay plan/import/ownership/ledger on MySQL. Core evidence and its permanent proof are explicit fixtures. */
@EnabledIfEnvironmentVariable(named="LEGACY_IMPORT_NATIVE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class LegacyCouponMigrationImportNativeMysqlTest {
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @Test void concurrentOriginalImportsCreateExactlyOneBaselineAndNoGrantNotification() throws Exception {
        String url=System.getenv("LEGACY_IMPORT_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/legacy_import_native_fixture\\?.*");
        var ds=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(ds);
        var ddl=new ResourceDatabasePopulator();
        for(String file:List.of("V478__entitlement_catalog.sql","V482__pay_entitlement_event_outbox_inbox.sql",
                "V486__entitlement_issue_capacity.sql","V487__entitlement_grants_and_ledger.sql",
                "V489__entitlement_use_and_daily_quota.sql","V511__entitlement_owner_registry.sql",
                "V515__entitlement_migration_evidence_stage.sql","V516__entitlement_manifest_stage_metadata.sql",
                "V517__entitlement_migration_alias.sql","V521__legacy_coupon_usage_facts.sql",
                "V522__legacy_coupon_target_plan.sql","V524__legacy_coupon_target_import.sql",
                "V525__legacy_coupon_historical_usage.sql"))
            ddl.addScript(new ClassPathResource("db/migration/"+file));
        ddl.execute(ds);
        var cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);
        var plugins=new MybatisPlusInterceptor();plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);factory.setPlugins(plugins);
        var sessions=factory.getObject();var json=new ObjectMapper();
        long from=Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS).toEpochMilli(),until=from+3600000;
        String body="""
            {"manifestVersion":2,"fenceWatermarkKind":"VISIBLE_V1","source":{"sourceSystem":"PMT","sourceKind":"TEMPLATE","sourceNo":"T1"},"batchNo":"B1",
             "template":{"couponNo":"T1","entityNo":"M1","title":"old","funder":"MERCHANT","status":"ACTIVE",
               "benefitMode":"CASH","benefitValue":100,"minAmountMinor":0,"timesTotal":1,"validityMode":"ABSOLUTE",
               "startAt":FROM,"endAt":UNTIL,"issueMode":"CENTER","redeemMode":"ORDER","receivedCount":1,"totalCount":10,"perUserLimit":1,"budgetMinor":1000,
               "migrationState":"FROZEN","migrationBatchNo":"B1","migrationWatermark":"WATERMARK","migrationFrozenRevision":2},
             "instances":[{"userCouponNo":"OLD1","couponNo":"T1","userNo":"U1","entityNo":"M1","status":"UNUSED","timesUsed":0,"receivedAt":FROM,"expireAt":UNTIL,"deleted":0}],
             "applications":[],"issues":[],"scopes":[],"historyAvailable":true,
             "assessmentFacts":{"version":1,"entityActive":true,"market":"CN","peerTemplateCount":0,"peerInstanceCount":0}}
            """.replace("FROM",Long.toString(from)).replace("UNTIL",Long.toString(until)).replace("WATERMARK","a".repeat(64));
        var identity=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,"T1");
        var snapshot=new LegacyCouponFrozenSnapshotPort.Snapshot(1,identity,"M1","B1",2,"a".repeat(64),EntitlementRecords.digest(body),body);
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var assessment=new LegacyCouponMigrationNormalizer().normalize("MS1",json.readTree(body),
                new MarketBrief("CN","China","CNY",2,"Asia/Shanghai",1,true),Instant.now());
        assertThat(assessment.status()).as(assessment.toString()).isEqualTo("NORMALIZED");
        var migration=mock(EntitlementMigrationService.class);when(migration.assess(scope,"MS1")).thenReturn(assessment);
        var hash=new AtomicReference<String>();var available=new AtomicBoolean(true);var reads=new AtomicInteger();
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class,EntitlementCommandDeduplicator.class,LegacyMigrationEvidenceReader.class,
                    EntitlementMigrationPlanWriter.class,EntitlementMigrationPlanServiceImpl.class,
                    LegacyCouponMigrationImportWriter.class,LegacyCouponMigrationImportServiceImpl.class,LegacyCouponHistoricalUsageWriter.class,
                    EntitlementPostingServiceImpl.class,EntitlementOwnerRegistryServiceImpl.class);
            context.registerBean("payTxManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
            context.registerBean(ObjectMapper.class,()->json);
            context.registerBean(LegacySnapshotCipher.class,()->new LegacySnapshotCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","NATIVE"));
            context.registerBean(EntitlementMigrationService.class,()->migration);
            context.registerBean(LegacyCouponMigrationManifestPort.class,()->(ctx,q)->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return snapshot;
            });
            context.registerBean(LegacyCouponSourceCommitPort.class,()->(ctx,source)->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();reads.incrementAndGet();
                if(!available.get()) throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                return Optional.of(new CommitProof(source,"B1","MD1",hash.get(),Instant.ofEpochMilli(from)));
            });
            for(Class<?> mapper:List.of(EntMigrationStageMapper.class,EntMigrationPlanMapper.class,EntMigrationAliasMapper.class,
                    EntMigrationImportMapper.class,EntMigrationAssetMapper.class,EntTemplateMapper.class,EntTemplateVersionMapper.class,
                    EntInstanceMapper.class,EntIssuePolicyMapper.class,EntIssueRecipientMapper.class,EntOwnerRegistryMapper.class,
                    EntLedgerMapper.class,PayEntCommandDedupMapper.class,EntUsageMapper.class,EntMigrationUsageMapper.class)) register(context,mapper,sessions);
            context.refresh();
            var evidence=context.getBean(LegacyMigrationEvidenceReader.class);var cipher=context.getBean(LegacySnapshotCipher.class);
            var stage=new EntMigrationStage();EntitlementRecords.audit(stage,new EntitlementContext("MAIN","OP1","S1","d".repeat(64)));
            stage.setStageNo("MS1");stage.setSourceSystem("PMT");stage.setSourceTemplateNo("T1");stage.setEntityNo("M1");
            stage.setMigrationBatchNo("B1");stage.setRequestKey("S1");stage.setCommandHash("d".repeat(64));stage.setReason("native evidence fixture");
            stage.setSourceSchemaVersion(1);stage.setFrozenRevision(2L);stage.setWatermark("a".repeat(64));stage.setContentHash(snapshot.contentHash());
            stage.setCryptoKeyId("NATIVE");stage.setPayloadBytes(body.getBytes(StandardCharsets.UTF_8).length);
            stage.setInstanceCount(1L);stage.setHistoryCount(0L);stage.setManifestVersion(2);stage.setHistoryAvailable(true);stage.setStatus("STAGED");
            stage.setPayloadCipher(cipher.encrypt(body,evidence.aad(stage)));context.getBean(EntMigrationStageMapper.class).insert(stage);
            var planned=context.getBean(EntitlementMigrationPlanService.class).prepare(scope,"MS1","fixed target","P1");hash.set(planned.planHash());
            var importer=context.getBean(LegacyCouponMigrationImportService.class);
            var attempts=new java.util.ArrayList<Callable<ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView>>();
            for(int i=0;i<20;i++) attempts.add(()->importer.importPlan(scope,planned.planNo(),"import baseline","I1"));
            try(var pool=Executors.newFixedThreadPool(6)) {
                for(var result:pool.invokeAll(attempts,30,TimeUnit.SECONDS)) assertThat(result.get().status()).isEqualTo("IMPORTED");
            }
            var ctx=new EntitlementContext("MAIN","OP1","VERIFY","a".repeat(64));
            var owner=context.getBean(EntitlementOwnerRegistryService.class).resolve(ctx,new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"OLD1"));
            assertThat(owner.ownerSystem()).isEqualTo(OwnerSystem.PAY);assertThat(owner.cutoverWatermark()).isEqualTo(planned.planHash());
            for(String table:List.of("ent_migration_import","ent_migration_asset","ent_instance","ent_ledger","ent_template"))
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_owner_registry",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT units_delta FROM ent_ledger WHERE biz_type='MIGRATION_BASELINE'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT source_paid_at FROM ent_instance",java.time.LocalDateTime.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT issued_count FROM ent_issue_policy",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT enabled FROM ent_issue_policy",Integer.class)).isZero();
            for(String table:List.of("ent_grant","pay_ent_outbox")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
            available.set(false);int prior=reads.get();
            assertThat(importer.importPlan(scope,planned.planNo(),"import baseline","I1").planHash()).isEqualTo(planned.planHash());
            assertThat(reads.get()).isEqualTo(prior);
        }
    }
    private static <T> void register(AnnotationConfigApplicationContext context,Class<T> type,SqlSessionFactory sessions) {
        context.registerBean(type.getName(),MapperFactoryBean.class,()->{var mapper=new MapperFactoryBean<T>(type);mapper.setSqlSessionFactory(sessions);return mapper;});
    }
}
