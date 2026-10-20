package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:legacy-migration-import;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.core.entitlement.migration-watermark-key=import-source-watermark-key-over-thirty-two-characters",
        "shop.core.entitlement.migration-commit.enabled=true","shop.pay.entitlement.catalog.enabled=true","shop.pay.entitlement.use.enabled=true","shop.pay.entitlement.legacy-coupon.enabled=true",
        "shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.pay.entitlement.migration-snapshot-key-id=IMPORTTEST"})
@ActiveProfiles("test")
class LegacyCouponMigrationImportFlowTest {
    @Autowired ai.neargo.shop.promotion.service.CouponService coupons;
    @Autowired org.springframework.transaction.PlatformTransactionManager coreTransactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired EntitlementMigrationAppService migration;
    @Autowired ai.neargo.shop.paybridge.LegacyCouponMigrationCommitCoordinator source;
    @Autowired EntitlementOwnerRegistryPort owners;
    @Autowired ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService importer;
    @Autowired ai.neargo.shop.pay.migration.service.LegacyCouponMigrationRecoveryService recovery;
    @Autowired ai.neargo.shop.spi.entitlement.LegacyCouponUsePort uses;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort proofs;

    @Test void realSourceDecisionImportsOneBaselineAndAllAliasesAtomicallyWithoutIssuingAgain() throws Exception {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,true);
        source.commit(f.scope(),f.planNo(),"commit source","C"+f.id());
        var receipt=importer.importPlan(f.scope(),f.planNo(),"import fixed plan","I"+f.id());
        assertThat(receipt.status()).isEqualTo("IMPORTED");assertThat(receipt.assetCount()).isEqualTo(1);
        assertThat(receipt.issuanceEnabledAtImport()).isFalse();
        var origin=new EntitlementOwnerRegistryPort.SourceIdentity(EntitlementOwnerRegistryPort.SourceSystem.PMT,
                EntitlementOwnerRegistryPort.SourceKind.INSTANCE,"UC"+f.id());
        var primary=owners.resolve(f.ctx(),origin);
        var shadow=owners.resolve(f.ctx(),new EntitlementOwnerRegistryPort.SourceIdentity(EntitlementOwnerRegistryPort.SourceSystem.MKT_LEGACY,
                EntitlementOwnerRegistryPort.SourceKind.INSTANCE,"UC"+f.id()));
        assertThat(primary.ownerSystem()).isEqualTo(EntitlementOwnerRegistryPort.OwnerSystem.PAY);
        assertThat(shadow.targetNo()).isEqualTo(primary.targetNo());assertThat(primary.cutoverWatermark()).isEqualTo(f.planHash());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=? AND biz_type='MIGRATION_BASELINE'",Integer.class,primary.targetNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_paid_at FROM ent_instance WHERE instance_no=?",java.time.LocalDateTime.class,primary.targetNo())).isNull();
        var estimate=uses.estimate(f.ctx(),new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.EstimateCommand("U"+f.id(),f.scope().entityNo(),origin,
                primary.targetNo(),f.batch(),f.planHash(),"CNY",java.util.List.of(new ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup("G1",f.scope().entityNo(),null,500))));
        assertThat(estimate.discountMinor()).isEqualTo(100);
        org.mockito.Mockito.doThrow(ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE))
                .when(proofs).decided(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThat(importer.importPlan(f.scope(),f.planNo(),"import fixed plan","I"+f.id())).isEqualTo(receipt);
        assertThat(importer.get(f.scope(),f.planNo())).isEqualTo(receipt);
        assertThat(importer.importPlan(f.scope(),f.planNo(),"already imported query","FRESH"+f.id())).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox",Integer.class)).isZero();
    }
    @Test void missingSourceProofAndLateMappingFailureNeverExposePartialAssetsAndOriginalImportKeyRecovers() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);
        assertThatThrownBy(()->importer.importPlan(f.scope(),f.planNo(),"import fixed plan","I"+f.id()))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        source.commit(f.scope(),f.planNo(),"source commit","C"+f.id());
        long ledgerBefore=jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger",Long.class);
        jdbc.update("INSERT INTO ent_migration_asset(tenant_no,import_no,source_system,source_instance_no,archival_only,total_units,baseline_remaining_units,created_at,updated_at,created_by,updated_by) VALUES('MAIN','OBSTRUCTION','PMT',?,1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')","UC"+f.id());
        assertThatThrownBy(()->importer.importPlan(f.scope(),f.planNo(),"import fixed plan","I"+f.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_template WHERE entity_no=?",Integer.class,f.scope().entityNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger",Long.class)).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_owner_registry WHERE source_no IN (?,?)",Integer.class,f.source().sourceNo(),"UC"+f.id())).isZero();
        assertThat(fences.state(f.ctx(),f.source()).state()).isEqualTo("COMMIT_DECIDED");
        jdbc.update("DELETE FROM ent_migration_asset WHERE tenant_no='MAIN' AND import_no='OBSTRUCTION' AND source_instance_no=?","UC"+f.id());
        assertThat(importer.importPlan(f.scope(),f.planNo(),"import fixed plan","I"+f.id()).status()).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger",Long.class)).isEqualTo(ledgerBefore+1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox",Integer.class)).isZero();
    }
    @Test void pendingSourceIsNotCommittedByRecoveryAndRealCommittedSourceRollsForwardWithoutManualImport() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);
        var pending=recovery.recover("MAIN",0,100);
        assertThat(pending.completed()).isZero();assertThat(pending.deferred()).isEqualTo(1);
        assertThat(fences.state(f.ctx(),f.source()).state()).isEqualTo("FROZEN");
        source.commit(f.scope(),f.planNo(),"commit source","C"+f.id());
        var resumed=recovery.recover("MAIN",0,100);
        assertThat(resumed.completed()).isEqualTo(1);assertThat(resumed.failed()).isZero();
        assertThat(importer.get(f.scope(),f.planNo()).assetCount()).isEqualTo(1);
        assertThat(recovery.recover("MAIN",0,100).scanned()).isZero();
    }
    @Test void originalDefinitionEntryReadsPayPauseAndResumesOnlyByExplicitMerchantAction() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);
        source.commit(f.scope(),f.planNo(),"definition source","C"+f.id());
        importer.importPlan(f.scope(),f.planNo(),"definition baseline","I"+f.id());
        var view=coupons.detail(f.scope().entityNo(),f.source().sourceNo());
        assertThat(view.status()).isEqualTo("PAUSED");assertThat(view.receivedCount()).isEqualTo(1);
        var resumed=coupons.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT enabled FROM ent_issue_policy WHERE template_no IN (SELECT template_no FROM ent_template WHERE entity_no=?)",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT migration_state FROM pmt_coupon WHERE coupon_no=?",String.class,f.source().sourceNo())).isEqualTo("COMMIT_DECIDED");
        assertThat(jdbc.queryForObject("SELECT received_count FROM pmt_coupon WHERE coupon_no=?",Integer.class,f.source().sourceNo())).isEqualTo(1);
        var saved=coupons.save(f.scope().entityNo(),new ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd(
                view.couponNo(),"updated future coupon","CASH",200L,null,null,0L,view.minQty(),"ALL",java.util.List.of(),null,
                view.validityMode(),view.startAt(),view.endAt(),view.validDays(),"CENTER","ORDER",1,10,1,3000L),"OP1");
        assertThat(saved.benefitValue()).isEqualTo(200);assertThat(saved.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT benefit_value FROM pmt_coupon WHERE coupon_no=?",Long.class,f.source().sourceNo())).isEqualTo(100);
        var frozenRule=jdbc.queryForObject("SELECT rule_snapshot FROM ent_instance WHERE entity_no=?",String.class,f.scope().entityNo());
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(frozenRule).path("benefitValue").asLong()).isEqualTo(100);
        coupons.setStatus(f.scope().entityNo(),view.couponNo(),"PAUSED");
        var paused=coupons.save(f.scope().entityNo(),new ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd(
                view.couponNo(),"paused future coupon","CASH",200L,null,null,0L,view.minQty(),"ALL",java.util.List.of(),null,
                view.validityMode(),view.startAt(),view.endAt(),view.validDays(),"CENTER","ORDER",1,10,1,3000L),"OP1");
        assertThat(paused.status()).isEqualTo("PAUSED");
        var version=jdbc.queryForObject("SELECT current_version_no FROM ent_template WHERE entity_no=?",String.class,f.scope().entityNo());
        var versions=jdbc.queryForObject("SELECT COUNT(*) FROM ent_template_version WHERE template_no IN (SELECT template_no FROM ent_template WHERE entity_no=?)",Integer.class,f.scope().entityNo());
        assertThatThrownBy(()->coupons.save(f.scope().entityNo(),new ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd(
                view.couponNo(),"must rollback","CASH",300L,null,null,0L,view.minQty(),"ALL",java.util.List.of(),null,
                view.validityMode(),view.startAt(),view.endAt(),view.validDays(),"CENTER","ORDER",1,0,1,3000L),"OP1"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT current_version_no FROM ent_template WHERE entity_no=?",String.class,f.scope().entityNo())).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_template_version WHERE template_no IN (SELECT template_no FROM ent_template WHERE entity_no=?)",Integer.class,f.scope().entityNo())).isEqualTo(versions);
        assertThat(coupons.detail(f.scope().entityNo(),view.couponNo()).title()).isEqualTo("paused future coupon");


    }
    @Test void legacyReadInsideAnExistingCoreTransactionStillWorksBeforeOwnerCommit() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);
        var tx=new org.springframework.transaction.support.TransactionTemplate(coreTransactions);
        var result=tx.execute(s->coupons.detail(f.scope().entityNo(),f.source().sourceNo()));
        assertThat(result.title()).isEqualTo("old");
    }
}
