package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:ent-migration-stage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.core.entitlement.migration-watermark-key=stage-test-source-key-at-least-thirty-two-characters",
        "shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.pay.entitlement.migration-snapshot-key-id=TEST"})
@ActiveProfiles("test")
@Import(EntitlementMigrationStageFlowTest.ObservedSource.class)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class EntitlementMigrationStageFlowTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class ObservedSource {
        static final java.util.concurrent.atomic.AtomicInteger CALLS=new java.util.concurrent.atomic.AtomicInteger();
        @Bean @Primary ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort observedManifest(LegacyCouponSourceFenceService fences) {
            return (ctx,q) -> {
                CALLS.incrementAndGet();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return ((ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort)fences).exportManifest(ctx,q);
            };
        }
        @Bean @Primary LegacyCouponFrozenSnapshotPort observedSource(LegacyCouponSourceFenceService fences) {
            return (ctx,q) -> {
                CALLS.incrementAndGet();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .as("source evidence is fetched outside the pay transaction").isFalse();
                return ((LegacyCouponFrozenSnapshotPort)fences).exportFrozen(ctx,q);
            };
        }
    }
    @Autowired EntitlementMigrationAppService migration;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext application;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired LegacyCouponFrozenSnapshotPort snapshots;
    @Autowired ai.neargo.shop.pay.migration.service.LegacySnapshotCipher cipher;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("transactionManager")
    org.springframework.transaction.PlatformTransactionManager coreTx;

    @Test void actualFrozenEvidenceIsEncryptedScopedAndIdempotentWithoutIssuingAssets() {
        String suffix=UUID.randomUUID().toString(), template="T"+suffix, code="OLD-"+suffix.substring(0,20);
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,tenant_no,"
                + "created_at,updated_at) VALUES(?,'stage','CASH',100,'MERCHANT','M1','MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP)",template);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,redeem_code,"
                + "received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,?,1,"
                + "1800000000000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","U"+suffix,template,code);
        String batch="B"+suffix,key="K"+suffix;
        var ctx=new EntitlementContext("MAIN","MIGRATION",key,"a".repeat(64));
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,template);
        var draining=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"stage test","D"+suffix));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,draining.revision(),"stage test","F"+suffix));
        var query=new LegacyCouponFrozenSnapshotPort.Query(source,batch,frozen.frozenRevision(),frozen.watermark());
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var command=new StageCommand(query,"offline evidence rehearsal");
        var staged=migration.stage(scope,command,key);
        assertThat(staged.status()).isEqualTo("STAGED");
        assertThatThrownBy(() -> migration.stage(new CatalogScope("MAIN","M1","STAFF",false),command,"DENIED"+suffix))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> migration.stage(new CatalogScope("OTHER","M1","OP1",true),command,"OTHER"+suffix))
                .isInstanceOf(BizException.class);
        assertThat(staged.importReady()).isFalse();
        assertThat(staged.instanceCount()).isEqualTo(1);
        assertThat(migration.stage(scope,command,key)).isEqualTo(staged);
        assertThat(migration.get(scope,staged.stageNo())).isEqualTo(staged);
        var original=snapshots.exportFrozen(ctx,query);
        String oldCommandHash=ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest(
                json.writeValueAsString(java.util.List.of(scope.entityNo(),scope.actorNo(),command)));
        String oldAad=json.writeValueAsString(java.util.List.of("LEGACY_STAGE_V1","MAIN",staged.stageNo(),"M1","PMT",
                template,batch,1,query.frozenRevision(),query.watermark(),original.contentHash(),oldCommandHash,"OP1",
                key,command.reason(),"TEST",original.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,1L,0L));
        String oldCipher=cipher.encrypt(original.payload(),oldAad);
        // Simulate an old writer's row after the additive schema upgrade; V1 binding is byte-compatible.
        jdbc.update("UPDATE ent_migration_stage SET payload_cipher=?,manifest_version=1,history_available=NULL WHERE stage_no=?",
                oldCipher,staged.stageNo());
        assertThat(migration.verify(scope,staged.stageNo()).status()).isEqualTo("SOURCE_MATCHED");
        assertThatThrownBy(() -> migration.assess(scope,staged.stageNo())).isInstanceOf(BizException.class);
        int reads=ObservedSource.CALLS.get();
        new org.springframework.transaction.support.TransactionTemplate(coreTx).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> migration.stage(scope,command,key)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> migration.verify(scope,staged.stageNo())).isInstanceOf(BizException.class);
        });
        assertThat(ObservedSource.CALLS.get()).isEqualTo(reads);
        jdbc.update("UPDATE ent_migration_stage SET reason='tampered audit' WHERE stage_no=?",staged.stageNo());
        assertThatThrownBy(() -> migration.verify(scope,staged.stageNo())).isInstanceOf(BizException.class);
        jdbc.update("UPDATE ent_migration_stage SET reason=? WHERE stage_no=?",command.reason(),staged.stageNo());
        String encrypted=jdbc.queryForObject("SELECT payload_cipher FROM ent_migration_stage WHERE stage_no=?",
                String.class,staged.stageNo());
        assertThat(encrypted).startsWith("v1.TEST.").doesNotContain(code,"redeemCode");
        assertThatThrownBy(() -> migration.get(new CatalogScope("MAIN","M2","OP1",true),staged.stageNo()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> migration.stage(scope,new StageCommand(query,"changed reason"),key))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> migration.stage(scope,command,"NEW"+suffix)).isInstanceOf(BizException.class);
        fences.transition(ctx,source,new Command(Action.ABORT,batch,frozen.revision(),"source retains ownership","A"+suffix));
        assertThat(migration.stage(scope,command,key)).isEqualTo(staged);
        assertThatThrownBy(() -> migration.verify(scope,staged.stageNo())).isInstanceOf(BizException.class);
        Object configured=org.springframework.test.util.ReflectionTestUtils.getField(cipher,"configuredKey");
        int beforeMissingKey=ObservedSource.CALLS.get();
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(cipher,"configuredKey","");
            assertThat(migration.stage(scope,command,key)).isEqualTo(staged);
            var unavailable=new StageCommand(new LegacyCouponFrozenSnapshotPort.Query(source,"NO-KEY"+suffix,
                    query.frozenRevision(),query.watermark()),"must fail before fetching source");
            assertThatThrownBy(() -> migration.stage(scope,unavailable,"NO-KEY"+suffix)).isInstanceOf(BizException.class);
            assertThat(ObservedSource.CALLS.get()).isEqualTo(beforeMissingKey);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(cipher,"configuredKey",configured); }
        for(String table:new String[]{"ent_owner_registry","ent_grant","ent_instance","ent_ledger","pay_ent_outbox"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_stage WHERE stage_no=?",Integer.class,staged.stageNo())).isEqualTo(1);
    }
    @Test void manifestStageIncludesTombstonesAndRechecksTheEntireContentRatherThanOnlyVisibleWatermark() {
        String id=UUID.randomUUID().toString(),template="MT"+id,hidden="MH"+id,batch="MB"+id,key="MK"+id;
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,tenant_no,"
                + "created_at,updated_at) VALUES(?,'full','CASH',100,'MERCHANT','M1','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                + "received_at,expire_at,deleted,tenant_no,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,1,"
                + "1800000000000,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "(?,?,'U1','M1','USED',1,1,1800000000000,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","MV"+id,template,hidden,template);
        jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,amount_minor,applied_at,deleted,"
                + "tenant_no,created_at,updated_at) VALUES(?,'COUPON',?,'U1',100,1,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","MA"+id,hidden);
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,template);
        var ctx=new EntitlementContext("MAIN","MIGRATION",key,"a".repeat(64));
        var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"full manifest","MD"+id));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"full manifest","MF"+id));
        var command=new StageCommand(new LegacyCouponFrozenSnapshotPort.Query(source,batch,frozen.frozenRevision(),
                frozen.watermark()),"full manifest");
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var full=migration.stageManifest(scope,command,key);
        assertThat(full.manifestVersion()).isEqualTo(2);
        assertThat(full.historyAvailable()).isTrue();
        var assessment=migration.assess(scope,full.evidence().stageNo());
        assertThat(assessment.status()).isEqualTo("BLOCKED");
        assertThat(assessment.importReady()).isFalse();
        assertThat(assessment.blockers()).isNotEmpty();
        assertThatThrownBy(() -> migration.assess(new CatalogScope("MAIN","M2","OP1",true),
                full.evidence().stageNo())).isInstanceOf(BizException.class);

        assertThat(full.evidence().instanceCount()).isEqualTo(2);
        assertThat(full.evidence().historyCount()).isEqualTo(1);
        assertThat(full.evidence().importReady()).isFalse();
        assertThat(migration.stageManifest(scope,command,key)).isEqualTo(full);
        assertThatThrownBy(() -> migration.stage(scope,command,key)).isInstanceOf(BizException.class);
        assertThat(migration.verify(scope,full.evidence().stageNo()).status()).isEqualTo("SOURCE_MATCHED");
        // This tombstone was outside the V1 visible watermark; the full source hash must still catch it.
        jdbc.update("UPDATE pmt_user_coupon SET times_used=0 WHERE user_coupon_no=?",hidden);
        assertThatThrownBy(() -> migration.verify(scope,full.evidence().stageNo())).isInstanceOf(BizException.class);
        jdbc.update("UPDATE pmt_user_coupon SET times_used=1 WHERE user_coupon_no=?",hidden);
        assertThat(migration.verify(scope,full.evidence().stageNo()).status()).isEqualTo("SOURCE_MATCHED");
    }
    @Test void legacyManifestDoesNotRepresentUnrecordedUsageHistoryAsKnownZero() {
        String id=UUID.randomUUID().toString(),template="LT"+id,batch="LB"+id,key="LK"+id;
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,start_at,end_at,tenant_no,"
                + "created_at,updated_at) VALUES(?,'legacy manifest','FULL_CUT',100,'MERCHANT','M1',1,1800000000000,"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template);
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,used_at,tenant_no,"
                + "created_at,updated_at) VALUES(?,?,'U1','USED',1,2,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","LU"+id,template);
        var source=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,template);
        var ctx=new EntitlementContext("MAIN","MIGRATION",key,"a".repeat(64));
        var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"legacy facts","LD"+id));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"legacy facts","LF"+id));
        var full=migration.stageManifest(new CatalogScope("MAIN","M1","OP1",true),new StageCommand(
                new LegacyCouponFrozenSnapshotPort.Query(source,batch,frozen.frozenRevision(),frozen.watermark()),"preserve unknown history"),key);
        assertThat(full.historyAvailable()).isFalse();
        assertThat(full.evidence().historyCount()).isZero(); // Captured application rows, not a claim of zero historical uses.
        assertThat(full.evidence().instanceCount()).isEqualTo(1);
        assertThat(full.evidence().importReady()).isFalse();
    }
    @Test void normalizationUsesActualMerchantAndPayMarketAndRejectsNewCrossSourceOverlap() {
        String suffix=UUID.randomUUID().toString(),entity="NE"+suffix,template="NT"+suffix,instance="NU"+suffix;
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at)"
                +" VALUES(?,'OWNER','normalization','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity);
        long received=System.currentTimeMillis()-10000,expires=received+3600000;
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,benefit_cap_minor,funder,entity_no,status,"
                +"scope_type,min_qty,validity_mode,start_at,end_at,issue_mode,redeem_mode,times_total,received_count,"
                +"tenant_no,created_at,updated_at) VALUES(?,'normalize','PERCENT',8500,200,'MERCHANT',?,'ACTIVE',"
                +"'GOODS',4,'ABSOLUTE',?,?,'CENTER','ORDER',3,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                template,entity,received+1000,expires);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                +"received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,'NUSER',?,'UNUSED',0,?,?,'MAIN',"
                +"CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",instance,template,entity,received,expires);
        String batch="NB"+suffix;
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,template);
        var ctx=new EntitlementContext("MAIN","MIGRATION","NK"+suffix,"a".repeat(64));
        var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"normalization","ND"+suffix));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"normalization","NF"+suffix));
        var scope=new CatalogScope("MAIN",entity,"OP1",true);
        var full=migration.stageManifest(scope,new StageCommand(new LegacyCouponFrozenSnapshotPort.Query(
                source,batch,frozen.frozenRevision(),frozen.watermark()),"normalization"),"NS"+suffix);
        var report=migration.assess(scope,full.evidence().stageNo());
        assertThat(report.status()).isEqualTo("NORMALIZED");
        assertThat(report.importReady()).isFalse();
        assertThat(report.definition().currency()).isEqualTo("CNY");
        assertThat(report.definition().benefitValue()).isEqualTo(8500);
        assertThat(report.instances().getFirst().effectiveFrom()).isEqualTo(java.time.Instant.ofEpochMilli(received));
        assertThat(report.notices()).contains("LEGACY_ORDER_SCOPE_NOT_ENFORCED","LEGACY_ORDER_MIN_QTY_NOT_ENFORCED");
        var planner=application.getBean(ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService.class);
        var planned=planner.prepare(scope,full.evidence().stageNo(),"fixed target plan","PLAN"+suffix);
        assertThat(planned.status()).isEqualTo("PLANNED");assertThat(planned.importReady()).isFalse();
        assertThat(planned.assetCount()).isEqualTo(1);assertThat(planned.archivalCount()).isZero();
        assertThat(planned.versionCount()).isEqualTo(2);
        assertThat(planner.prepare(scope,full.evidence().stageNo(),"fixed target plan","PLAN"+suffix)).isEqualTo(planned);
        assertThat(planner.prepare(scope,full.evidence().stageNo(),"fixed target plan","ANOTHER"+suffix)).isEqualTo(planned);
        assertThat(planner.get(scope,planned.planNo())).isEqualTo(planned);
        assertThat(planner.verify(scope,planned.planNo())).isEqualTo(planned);
        assertThat(json.writeValueAsString(planned)).doesNotContain("NUSER","payloadCipher","userNo");
        assertThat(jdbc.queryForObject("SELECT payload_cipher FROM ent_migration_plan WHERE plan_no=?",String.class,planned.planNo()))
                .startsWith("v1.TEST.").doesNotContain("NUSER");
        assertThatThrownBy(()->planner.get(new CatalogScope("MAIN","M2","OP1",true),planned.planNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->planner.prepare(new CatalogScope("MAIN",entity,"OTHER",true),full.evidence().stageNo(),"fixed target plan","PLAN"+suffix)).isInstanceOf(BizException.class);
        String cipher=jdbc.queryForObject("SELECT payload_cipher FROM ent_migration_plan WHERE plan_no=?",String.class,planned.planNo());
        jdbc.update("UPDATE ent_migration_plan SET payload_cipher='invalid' WHERE plan_no=?",planned.planNo());
        assertThatThrownBy(()->planner.verify(scope,planned.planNo())).isInstanceOf(BizException.class);
        jdbc.update("UPDATE ent_migration_plan SET payload_cipher=? WHERE plan_no=?",cipher,planned.planNo());
        // A source collision appearing after capture invalidates the entire evidence, including tombstones.
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,start_at,end_at,deleted,tenant_no,"
                +"created_at,updated_at) VALUES(?,'shadow','FULL_CUT',100,'MERCHANT',?,1,1800000000000,1,'MAIN',CURRENT_TIMESTAMP,"
                +"CURRENT_TIMESTAMP)",template,entity);
        assertThatThrownBy(() -> migration.assess(scope,full.evidence().stageNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->planner.verify(scope,planned.planNo())).isInstanceOf(BizException.class);
        for(String table:new String[]{"ent_instance","ent_ledger","ent_owner_registry","pay_ent_outbox"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
        fences.transition(ctx,source,new Command(Action.ABORT,batch,frozen.revision(),"no cutover","NA"+suffix));
    }

    @Test void identicalFrozenV232FamiliesCanBeRecognizedOnceButOnlyTheCanonicalSourceIsAssessable() throws Exception {
        String suffix=UUID.randomUUID().toString(),entity="AE"+suffix,template="AT"+suffix,instance="AU"+suffix,batch="AB"+suffix;
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at)"
                +" VALUES(?,'OWNER','alias','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity);
        long from=System.currentTimeMillis()-10000,until=from+3600000;
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,min_amount_minor,funder,entity_no,"
                +"start_at,end_at,total_count,received_count,per_user_limit,budget_minor,tenant_no,created_at,updated_at)"
                +" VALUES(?,'alias','CASH',100,50,'MERCHANT',?,?,?,10,1,1,1000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                template,entity,from,until);
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,threshold_minor,funder,entity_no,"
                +"start_at,end_at,total_count,received_count,per_user_limit,budget_minor,tenant_no,created_at,updated_at)"
                +" VALUES(?,'alias','FULL_CUT',100,50,'MERCHANT',?,?,?,10,1,1,1000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                template,entity,from,until);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,received_at,"
                +"expire_at,tenant_no,created_at,updated_at) VALUES(?,?,'ALIAS-PRIVATE',?,'UNUSED',0,?,?,'MAIN',"
                +"CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",instance,template,entity,from,until);
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,created_at,"
                +"updated_at) VALUES(?,?,'ALIAS-PRIVATE','UNUSED',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                instance,template,from);
        var scope=new CatalogScope("MAIN",entity,"OP1",true);
        var staged=new java.util.ArrayList<ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView>();
        var ctx=new EntitlementContext("MAIN","MIGRATION","AK"+suffix,"a".repeat(64));
        var roots=new java.util.ArrayList<ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.FenceView>();
        for(var system:java.util.List.of(SourceSystem.PMT,SourceSystem.MKT_LEGACY)) {
            var source=new SourceIdentity(system,SourceKind.TEMPLATE,template);
            var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"alias fixture","AD"+system+suffix));
            var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"alias fixture","AF"+system+suffix));
            roots.add(frozen);
            staged.add(migration.stageManifest(scope,new StageCommand(new LegacyCouponFrozenSnapshotPort.Query(source,batch,
                    frozen.frozenRevision(),frozen.watermark()),"alias fixture"),"AS"+system+suffix).evidence());
        }
        var pair=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair(staged.get(0).stageNo(),staged.get(1).stageNo());
        var candidate=migration.compareAlias(scope,pair);
        assertThat(candidate.status()).as(candidate.toString()).isEqualTo("CANDIDATE_ALIAS");
        assertThat(migration.assess(scope,pair.pmtStageNo()).blockers()).contains("SOURCE_NUMBER_COLLISION");
        var command=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize(pair,0,0,"offline V232 exact-copy recognition");
        String key="AR"+suffix;
        ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView alias;
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var go=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(() -> {go.await();return migration.recognizeAlias(scope,command,key);});
            var second=pool.submit(() -> {go.await();return migration.recognizeAlias(scope,command,key);});
            go.countDown();
            alias=first.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(alias);
        } finally {pool.shutdownNow();}
        assertThat(alias.status()).isEqualTo("RECOGNIZED");
        assertThat(alias.importReady()).isFalse();
        assertThat(alias.canonicalStageNo()).isEqualTo(pair.pmtStageNo());
        assertThat(alias.toString()).doesNotContain("ALIAS-PRIVATE");
        assertThat(migration.recognizeAlias(scope,command,key)).isEqualTo(alias);
        assertThat(migration.getAlias(scope,alias.aliasNo())).isEqualTo(alias);
        assertThat(migration.assess(scope,pair.pmtStageNo()).status()).isEqualTo("NORMALIZED");
        assertThat(migration.assess(scope,pair.mktStageNo()).blockers()).contains("ALIAS_SHADOW_SOURCE");
        var planner=application.getBean(ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService.class);
        var target=planner.prepare(scope,pair.pmtStageNo(),"recognized family target","AP"+suffix);
        assertThat(target.assetCount()).isEqualTo(1);assertThat(target.importReady()).isFalse();
        assertThat(planner.verify(scope,target.planNo())).isEqualTo(target);
        assertThatThrownBy(()->planner.prepare(scope,pair.mktStageNo(),"shadow cannot plan","ASH"+suffix)).isInstanceOf(BizException.class);

        assertThatThrownBy(() -> migration.recognizeAlias(scope,command,"OTHER"+suffix)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> migration.getAlias(new CatalogScope("MAIN","OTHER","OP1",true),alias.aliasNo()))
                .isInstanceOf(BizException.class);
        var old=roots.get(1);
        fences.transition(ctx,new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,template),
                new Command(Action.ABORT,batch,old.revision(),"source still owns assets","AA"+suffix));
        assertThat(migration.recognizeAlias(scope,command,key)).isEqualTo(alias); // Historical reply, never a fresh proof.
        assertThatThrownBy(() -> migration.assess(scope,pair.pmtStageNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->planner.verify(scope,target.planNo())).isInstanceOf(BizException.class);
        for(String table:new String[]{"ent_instance","ent_ledger","ent_owner_registry","pay_ent_outbox"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
    }

}
