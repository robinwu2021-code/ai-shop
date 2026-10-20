package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.paybridge.*;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand;
import ai.neargo.shop.support.StandalonePayFixture;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;

/** Actual migration, new issuance and original checkout across separate owner databases/JVMs. */
@EnabledIfEnvironmentVariable(named="ENTITLEMENT_DUAL_PROCESS",matches="1")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
        "shop.core.entitlement.migration-watermark-key=dual-migration-source-key-at-least-thirty-two-characters",
        "shop.core.entitlement.migration-commit.enabled=true","shop.core.entitlement.legacy-coupon.enabled=true"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyCouponMigrationTwoProcessAcceptanceTest {
    private static final StandalonePayFixture PAY=new StandalonePayFixture();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry p) throws Exception {
        PAY.start(p,"dual-legacy-migration-core",List.of("--shop.pay.entitlement.legacy-coupon.enabled=true",
                "--shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--shop.pay.entitlement.migration-snapshot-key-id=DUAL"));
    }
    @AfterAll static void close() throws Exception {PAY.close();}
    @Autowired EntitlementMigrationAppService migration;
    @Autowired LegacyCouponMigrationCommitCoordinator source;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired CouponService coupons;
    @Autowired LegacyCouponIssueAppService issues;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext app;
    @Test void sourceCommitImportClaimOriginalCheckoutAndCancellationKeepEachOwnerLocal() {
        assertThat(migration).isInstanceOf(ai.neargo.shop.payclient.impl.RemoteEntitlementMigrationAppService.class);
        var f=LegacyCouponMigrationFixture.prepareForEntity(jdbc,fences,migration,"M0001");String user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        source.commit(f.scope(),f.planNo(),"dual source","C"+f.id());
        var baseline=migration.importPlan(f.scope(),new ImportCommand(f.planNo(),"dual baseline"),"I"+f.id());
        assertThat(migration.getImport(f.scope(),f.planNo())).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM pmt_migration_decision",Integer.class)).isZero();
        assertThat(coupons.detail("M0001",f.source().sourceNo()).status()).isEqualTo("PAUSED");
        coupons.setStatus("M0001",f.source().sourceNo(),"ACTIVE");
        var claimed=coupons.receive(user,f.source().sourceNo());
        String issue=jdbc.queryForObject("SELECT issue_no FROM pmt_pay_coupon_projection WHERE instance_no=?",String.class,claimed.userCouponNo());
        var login=new ai.neargo.shop.auth.LoginUser(ai.neargo.shop.auth.Realm.CONSUMER,ai.neargo.auth.store.SubjectKind.USR,user,"Buyer",List.of(),List.of(),null,null);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,List.of()));
        try {
            var command=new ai.neargo.shop.trade.service.OrderService.CreateOrderCommand(
                    List.of(new ai.neargo.shop.trade.service.OrderService.CreateOrderCommand.Item("G0001","SK0001",1)),"STORE_PICKUP","PP0001",null,claimed.userCouponNo(),0L,null,null,null,null,null);
            var orders=app.getBean(ai.neargo.shop.trade.service.OrderService.class);var order=orders.create(command,"USE"+f.id());
            assertThat(issues.get(f.scope(),issue).usedCount()).isEqualTo(1);
            assertThat(issues.get(f.scope(),issue).usedAmountMinor()).isEqualTo(100);
            orders.cancel(order.payOrderNo(),"dual cancellation");
            var restored=app.getBean(EntitlementRecoveryService.class).recover("MAIN",ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind.LEGACY_COUPON,0,100);
            assertThat(restored.failed()).isZero();
            assertThat(issues.get(f.scope(),issue).usedCount()).isZero();
            assertThat(PAY.jdbc().queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,claimed.userCouponNo())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_user_coupon WHERE coupon_no=?",Integer.class,f.source().sourceNo())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger",Integer.class)).isZero();
            assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM ord_order",Integer.class)).isZero();
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void historicalCancellationRestoresTheImportedAssetOnceAcrossHttp() {
        var f=LegacyCouponMigrationFixture.prepareHistory(jdbc,fences,migration);
        source.commit(f.scope(),f.planNo(),"dual history source","C"+f.id());
        migration.importPlan(f.scope(),new ImportCommand(f.planNo(),"dual history"),"I"+f.id());
        jdbc.update("UPDATE ord_order SET status='CANCELLED',paid_at=NULL WHERE order_no=?","OLD-O"+f.id());
        jdbc.update("UPDATE ord_sub_order SET status='CANCELLED' WHERE sub_order_no=?","OLD-SO"+f.id());
        var router=app.getBean(ai.neargo.shop.portal.port.CouponPortRouter.class);
        router.release("OLD-O"+f.id());router.release("OLD-O"+f.id());
        var result=app.getBean(EntitlementRecoveryService.class).recover("MAIN",ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind.HISTORICAL_COUPON_RETURN,0,100);
        assertThat(result.failed()).isZero();
        assertThat(PAY.jdbc().queryForObject("SELECT remaining_units FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM ent_migration_return WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reverted_at FROM pmt_apply WHERE apply_no=?",Long.class,"AP"+f.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT pay_state FROM pmt_migration_return WHERE entity_no=?",String.class,f.scope().entityNo())).isEqualTo("RESTORED");
    }
}
