package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.payclient.impl.RemoteEntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.support.StandalonePayFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual app -> pay -> core source callback -> pay evidence transaction, across two JVMs/databases. */
@EnabledIfEnvironmentVariable(named="ENTITLEMENT_DUAL_PROCESS",matches="1")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
        "shop.core.entitlement.migration-watermark-key=dual-source-evidence-key-at-least-thirty-two-characters"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EntitlementMigrationStageTwoProcessAcceptanceTest {
    private static final StandalonePayFixture PAY=new StandalonePayFixture();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) throws Exception {
        PAY.start(properties,"dual-migration-core",List.of(
                "--shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--shop.pay.entitlement.migration-snapshot-key-id=TEST"));
    }
    @Autowired EntitlementMigrationAppService migration;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired JdbcTemplate jdbc;
    @Test void originalEvidenceAndCanonicalAliasStayInPayAndBothSourcesRemainAuthoritative() {
        assertThat(migration).isInstanceOf(RemoteEntitlementMigrationAppService.class);
        String id=UUID.randomUUID().toString(),template="T"+id,batch="B"+id,key="K"+id,secret="OLD-"+id.substring(0,20);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at)"
                +" VALUES('M1','OWNER','dual normalize','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,start_at,end_at,received_count,tenant_no,"
                + "created_at,updated_at) VALUES(?,'dual stage','CASH',100,'MERCHANT','M1',1,1800000000000,1,'MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP)",template);
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,start_at,end_at,received_count,"
                +"tenant_no,created_at,updated_at) VALUES(?,'dual stage','FULL_CUT',100,'MERCHANT','M1',1,1800000000000,1,"
                +"'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,redeem_code,"
                + "received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,?,1,"
                + "1800000000000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","U"+id,template,secret);
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,created_at,updated_at)"
                +" VALUES(?,?,'U1','UNUSED',1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","U"+id,template);
        var ctx=new EntitlementContext("MAIN","MIGRATION",key,"a".repeat(64));
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var roots=new java.util.ArrayList<ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.FenceView>();
        var stagedRows=new java.util.ArrayList<ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView>();
        for(var system:List.of(SourceSystem.PMT,SourceSystem.MKT_LEGACY)) {
            var source=new SourceIdentity(system,SourceKind.TEMPLATE,template);
            var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"dual rehearsal","D"+system+id));
            var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"dual rehearsal","F"+system+id));
            roots.add(frozen);
            var command=new StageCommand(new LegacyCouponFrozenSnapshotPort.Query(source,batch,frozen.frozenRevision(),
                    frozen.watermark()),"dual evidence");
            var manifest=migration.stageManifest(scope,command,"S"+system+id);
            assertThat(manifest.manifestVersion()).isEqualTo(2);
            assertThat(manifest.historyAvailable()).isEqualTo(system==SourceSystem.PMT);
            stagedRows.add(manifest.evidence());
        }
        var staged=stagedRows.getFirst();
        assertThat(staged.importReady()).isFalse();
        assertThat(migration.assess(scope,staged.stageNo()).blockers()).contains("SOURCE_NUMBER_COLLISION");
        var pair=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair(staged.stageNo(),stagedRows.getLast().stageNo());
        assertThat(migration.compareAlias(scope,pair).status()).isEqualTo("CANDIDATE_ALIAS");
        var recognition=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize(pair,0,0,"dual exact-copy recognition");
        var alias=migration.recognizeAlias(scope,recognition,key);
        assertThat(alias.importReady()).isFalse();
        assertThat(migration.getAlias(scope,alias.aliasNo())).isEqualTo(alias);
        assertThat(migration.recognizeAlias(scope,recognition,key)).isEqualTo(alias);
        var assessment=migration.assess(scope,staged.stageNo());
        assertThat(assessment.status()).isEqualTo("NORMALIZED");
        assertThat(assessment.importReady()).isFalse();
        assertThat(assessment.definition().currency()).isEqualTo("CNY");
        assertThat(assessment.instances()).hasSize(1);
        assertThat(assessment.toString()).doesNotContain(secret,"userNo=","redeemCode=");
        assertThat(migration.assess(scope,pair.mktStageNo()).blockers()).contains("ALIAS_SHADOW_SOURCE");
        assertThat(migration.get(scope,staged.stageNo())).isEqualTo(staged);
        assertThat(migration.verify(scope,staged.stageNo()).status()).isEqualTo("SOURCE_MATCHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_stage",Integer.class)).isZero();
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM ent_migration_stage",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_migration_alias",Integer.class)).isZero();
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM ent_migration_alias",Integer.class)).isEqualTo(1);
        assertThat(PAY.jdbc().queryForObject("SELECT payload_cipher FROM ent_migration_stage WHERE stage_no=?",String.class,
                staged.stageNo())).startsWith("v1.TEST.").doesNotContain(secret);
        // Aborting the shadow invalidates canonical assessment as well, even though primary evidence still matches.
        var shadow=roots.getLast();
        fences.transition(ctx,new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,template),
                new Command(Action.ABORT,batch,shadow.revision(),"end shadow rehearsal","AS"+id));
        assertThatThrownBy(() -> migration.assess(scope,staged.stageNo())).isInstanceOf(BizException.class);
        assertThat(migration.recognizeAlias(scope,recognition,key)).isEqualTo(alias);
        var primary=roots.getFirst();
        fences.transition(ctx,new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,template),
                new Command(Action.ABORT,batch,primary.revision(),"end primary rehearsal","AP"+id));
        assertThatThrownBy(() -> migration.verify(scope,staged.stageNo())).isInstanceOf(BizException.class);
        for(String table:List.of("ent_owner_registry","ent_instance","ent_ledger","pay_ent_outbox")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
            assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        }
    }
    @AfterAll static void cleanup() throws Exception { PAY.close(); }
}
