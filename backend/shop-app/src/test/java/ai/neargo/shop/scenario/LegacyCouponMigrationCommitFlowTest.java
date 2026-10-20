package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"shop.services.internal-token=commit-proof-test","spring.datasource.url=jdbc:h2:mem:legacy-migration-commit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.core.entitlement.migration-watermark-key=commit-source-watermark-key-over-thirty-two-characters",
        "shop.core.entitlement.migration-commit.enabled=true",
        "shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.pay.entitlement.migration-snapshot-key-id=COMMITTEST"})
@ActiveProfiles("test")
class LegacyCouponMigrationCommitFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired EntitlementMigrationAppService migration;
    @Autowired ai.neargo.shop.paybridge.LegacyCouponMigrationCommitCoordinator coordinator;
    @Autowired ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort proofs;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Test void realPreparedPlanCanProduceOneDurableSourceCommitWithoutImportingAnyRights() throws Exception {
        var f=prepare(false);
        var result=coordinator.commit(f.scope(),f.planNo(),"source commit","K"+f.id());
        assertThat(coordinator.commit(f.scope(),f.planNo(),"source commit","K"+f.id())).isEqualTo(result);
        assertThat(fences.state(f.ctx(),f.source()).state()).isEqualTo("COMMIT_DECIDED");
        var proof=proofs.decided(f.ctx(),f.source()).orElseThrow();
        assertThat(proof.planHash()).isEqualTo(f.planHash());assertThat(proof.decisionNo()).isEqualTo(result.decisionNo());
        assertThatThrownBy(()->fences.transition(f.ctx(),f.source(),new Command(Action.ABORT,f.batch(),3,"too late","LATE"+f.id())))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        var locator=new ai.neargo.shop.svc.ConfigServiceLocator();
        locator.getTargets().put(ai.neargo.shop.svc.ServiceName.PLATFORM,"http://127.0.0.1:"+port);
        var api=new ai.neargo.shop.svc.InternalHttp(locator,"commit-proof-test").client(ai.neargo.shop.svc.ServiceName.PLATFORM,
                ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        var query=new ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.MigrationCommitQuery(f.ctx(),f.source());
        assertThat(api.legacyMigrationCommit(query).proof()).isEqualTo(proof);
        var wrong=new ai.neargo.shop.svc.InternalHttp(locator,"wrong").client(ai.neargo.shop.svc.ServiceName.PLATFORM,
                ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        assertThatThrownBy(()->wrong.legacyMigrationCommit(query)).isInstanceOf(ai.neargo.svc.client.ServiceCallException.class);
        assertThat(api.legacyMigrationCommit(new ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.MigrationCommitQuery(f.ctx(),
                new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,"UNKNOWN"))).proof()).isNull();
        assertNoTargetWrites();
    }
    @Test void aliasSourcesAndJournalRollbackTogetherWhenSecondBindingFailsAndOriginalKeyCanRetry() {
        var f=prepare(true);
        jdbc.update("INSERT INTO pmt_migration_source(tenant_no,source_system,source_template_no,decision_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN','MKT_LEGACY',?,'OBSTRUCTION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')",f.source().sourceNo());
        assertThatThrownBy(()->coordinator.commit(f.scope(),f.planNo(),"source commit","K"+f.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(fences.state(f.ctx(),f.source()).state()).isEqualTo("FROZEN");
        var shadow=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,f.source().sourceNo());
        assertThat(fences.state(f.ctx(),shadow).state()).isEqualTo("FROZEN");
        assertThat(proofs.decided(f.ctx(),f.source())).isEmpty();assertThat(proofs.decided(f.ctx(),shadow)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_migration_decision WHERE plan_no=?",Integer.class,f.planNo())).isZero();
        jdbc.update("DELETE FROM pmt_migration_source WHERE tenant_no='MAIN' AND source_system='MKT_LEGACY' AND source_template_no=?",f.source().sourceNo());
        var result=coordinator.commit(f.scope(),f.planNo(),"source commit","K"+f.id());
        assertThat(proofs.decided(f.ctx(),f.source()).orElseThrow().decisionNo()).isEqualTo(result.decisionNo());
        assertThat(proofs.decided(f.ctx(),shadow).orElseThrow().decisionNo()).isEqualTo(result.decisionNo());
        assertNoTargetWrites();
    }
    private LegacyCouponMigrationFixture.Fixture prepare(boolean alias) {
        return LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,alias);
    }

    private void assertNoTargetWrites() {
        for(String table:new String[]{"ent_instance","ent_ledger","ent_owner_registry","pay_ent_outbox"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
    }
}
