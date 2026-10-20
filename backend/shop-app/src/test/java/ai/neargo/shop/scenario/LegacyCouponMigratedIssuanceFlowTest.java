package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.paybridge.LegacyCouponMigrationCommitCoordinator;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.user.service.PersonService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
/** Real registered member audience and payment issuance; no fabricated payment or client recipient list. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"shop.services.internal-token=legacy-issue-wire","spring.datasource.url=jdbc:h2:mem:migrated-coupon-issuance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.core.entitlement.migration-watermark-key=issue-source-watermark-key-over-thirty-two-characters",
        "shop.core.entitlement.migration-commit.enabled=true","shop.core.entitlement.legacy-coupon.enabled=true","shop.pay.entitlement.catalog.enabled=true",
        "shop.pay.entitlement.issue.enabled=true","shop.pay.entitlement.use.enabled=true","shop.pay.entitlement.legacy-coupon.enabled=true",
        "shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.pay.entitlement.migration-snapshot-key-id=ISSUETEST"})
@ActiveProfiles("test")
class LegacyCouponMigratedIssuanceFlowTest {
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired ai.neargo.shop.spi.marketing.CouponPort router;
    @Autowired org.springframework.context.ApplicationContext application;
    @Autowired ai.neargo.shop.pay.migration.service.LegacyCouponIssueService payIssues;
    @Autowired ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort owners;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ai.neargo.shop.pay.migration.service.LegacyCouponIssueWriter issueWriter;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ai.neargo.shop.spi.entitlement.LegacyCouponAudiencePort audienceOwner;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ai.neargo.shop.promotion.service.impl.PmtCouponServiceImpl frozenLegacyView;
    @Autowired JdbcTemplate jdbc;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired EntitlementMigrationAppService migration;
    @Autowired LegacyCouponMigrationCommitCoordinator source;
    @Autowired CouponService coupons;
    @Autowired MemberService members;
    @Autowired PersonService persons;
    private String phone() {return "139"+String.format("%08d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100000000));}
    private void enrolled(String entity,String user,boolean registered) {
        String phone=phone();persons.resolveOrCreateByPhone(phone);
        if(registered) persons.bindOnLogin(user,phone);
        members.enroll(entity,phone,null,List.of(),null,"OP1");
    }
    @Test void originalIssueEntryUsesRealAudienceAndOnePayIssuerWithoutWritingTheFrozenSource() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);
        String fresh="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",fresh);
        enrolled(f.scope().entityNo(),"U"+f.id(),true);enrolled(f.scope().entityNo(),fresh,true);enrolled(f.scope().entityNo(),null,false);
        var audience=java.util.List.of(new ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem("ALL",null));
        source.commit(f.scope(),f.planNo(),"issuance source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"issuance baseline"),"I"+f.id());
        coupons.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        var issued=coupons.issue(f.scope().entityNo(),f.source().sourceNo(),null,audience,"OP1");
        assertThat(issued.issued()).isEqualTo(1);assertThat(issued.skipped()).isEqualTo(2);assertThat(issued.planned()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_user_coupon WHERE coupon_no=?",Integer.class,f.source().sourceNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(2);
        assertThat(coupons.myCoupons(fresh)).hasSize(1);
        assertThat(coupons.issues(f.scope().entityNo(),f.source().sourceNo())).extracting(v->v.issueNo()).contains(issued.issueNo());
        var again=coupons.issue(f.scope().entityNo(),f.source().sourceNo(),null,audience,"OP1");
        assertThat(again.issued()).isZero();assertThat(again.skipped()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        var locator=new ai.neargo.shop.svc.ConfigServiceLocator();locator.getTargets().put(ai.neargo.shop.svc.ServiceName.PLATFORM,"http://127.0.0.1:"+port);
        var api=new ai.neargo.shop.svc.InternalHttp(locator,"legacy-issue-wire").client(ai.neargo.shop.svc.ServiceName.PLATFORM,
                ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        var query=new ai.neargo.shop.spi.entitlement.LegacyCouponAudiencePort.Query(f.source(),f.batch(),f.planHash(),f.scope().entityNo(),"TARGETED",null,audience);
        var request=new ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.LegacyAudience(f.ctx(),query);
        assertThat(api.legacyCouponAudience(request).unreachableCount()).isEqualTo(1);
        var wrong=new ai.neargo.shop.svc.InternalHttp(locator,"wrong").client(ai.neargo.shop.svc.ServiceName.PLATFORM,
                ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        assertThatThrownBy(()->wrong.legacyCouponAudience(request)).isInstanceOf(ai.neargo.svc.client.ServiceCallException.class);

    }
    @Test void originalConsumerClaimOnlyIssuesToTheActualClaimantAndUsesTheSharedPayAccount() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);String user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        source.commit(f.scope(),f.planNo(),"consumer source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"consumer baseline"),"I"+f.id());
        coupons.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        var claimed=coupons.receive(user,f.source().sourceNo());
        assertThat(claimed.usableNow()).isTrue();assertThat(coupons.myCoupons(user)).hasSize(1);
        assertThat(coupons.center(user).stream().filter(c->c.couponNo().equals(f.source().sourceNo())).findFirst().orElseThrow().received()).isTrue();
        assertThat(router.allocate(user,claimed.userCouponNo(),List.of(new ai.neargo.shop.spi.marketing.CouponPort.MerchantAmount(f.scope().entityNo(),500))).totalDiscount()).isEqualTo(100);
        assertThatThrownBy(()->coupons.receive(user,f.source().sourceNo())).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE entity_no=? AND user_no=?",Integer.class,f.scope().entityNo(),user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_user_coupon WHERE coupon_no=?",Integer.class,f.source().sourceNo())).isEqualTo(1);
    }
    @Test void acceptedNativeGrantsRecoverWithoutReselectingTheAudienceOrGrantingAgain() throws Exception {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);String user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        enrolled(f.scope().entityNo(),user,true);
        source.commit(f.scope(),f.planNo(),"recovery source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"recovery baseline"),"I"+f.id());
        coupons.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        var owner=owners.resolve(f.ctx(),f.source());
        var reference=new ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Reference(f.source(),owner.targetNo(),f.batch(),f.planHash());
        var command=new ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.Command(reference,"TARGETED",null,
                List.of(new ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem("ALL",null)));
        org.mockito.Mockito.doThrow(new IllegalStateException("INJECTED_FINALIZE_LOSS")).when(issueWriter).finish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(()->payIssues.issue(f.scope(),command,"REC"+f.id())).hasMessageContaining("INJECTED_FINALIZE_LOSS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_legacy_coupon_issue WHERE entity_no=?",String.class,f.scope().entityNo())).isEqualTo("PREPARED");
        org.mockito.Mockito.doCallRealMethod().when(issueWriter).finish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(new IllegalStateException("SOURCE_UNAVAILABLE")).when(audienceOwner).resolve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var recovery=application.getBean("legacyCouponIssueRecoveryService");
        var result=recovery.getClass().getMethod("recover",String.class,long.class,int.class).invoke(recovery,"MAIN",0L,20);
        assertThat(result.getClass().getMethod("completed").invoke(result)).isEqualTo(1);
        var accepted=payIssues.issue(f.scope(),command,"REC"+f.id());
        assertThat(accepted.status()).isEqualTo("COMPLETED");assertThat(accepted.issued()).isEqualTo(1);
        var event=new ai.neargo.shop.event.SysOutbox();
        event.setPayload(jdbc.queryForObject("SELECT payload FROM pay_ent_outbox WHERE aggregate_no=? AND event_type='LEGACY_COUPON_ISSUE_COMPLETED'",String.class,accepted.issueNo()));
        var consumer=application.getBean(ai.neargo.shop.paybridge.LegacyCouponIssueCompletedConsumer.class);
        consumer.consume(event);consumer.consume(event);
        assertThat(coupons.myCoupons(user)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_pay_coupon_projection WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
    }
    @Test void migratedCenterUsesTheCurrentPayCatalogEvenWhenTheFrozenLegacyViewOmitsItsCopy() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,false);String user="U"+UUID.randomUUID();
        source.commit(f.scope(),f.planNo(),"catalog source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"catalog baseline"),"I"+f.id());
        coupons.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        // The old read model may filter its frozen dates; it is not the owner of the new published window.
        org.mockito.Mockito.doReturn(List.of()).when(frozenLegacyView).center(user);
        assertThat(coupons.center(user)).extracting(c->c.couponNo()).contains(f.source().sourceNo());
    }
    @Test void newlyClaimedPayCouponCompletesTheRealOriginalCheckoutAndReportsItsActualUsage() throws Exception {
        var f=LegacyCouponMigrationFixture.prepareForEntity(jdbc,fences,migration,"M0001");String user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        source.commit(f.scope(),f.planNo(),"checkout source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"checkout baseline"),"I"+f.id());
        coupons.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        var claimed=coupons.receive(user,f.source().sourceNo());
        String issueNo=jdbc.queryForObject("SELECT issue_no FROM pmt_pay_coupon_projection WHERE instance_no=?",String.class,claimed.userCouponNo());
        var login=new ai.neargo.shop.auth.LoginUser(ai.neargo.shop.auth.Realm.CONSUMER,ai.neargo.auth.store.SubjectKind.USR,user,"Buyer",List.of(),List.of(),null,null);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,List.of()));
        try {
            var command=new ai.neargo.shop.trade.service.OrderService.CreateOrderCommand(
                    List.of(new ai.neargo.shop.trade.service.OrderService.CreateOrderCommand.Item("G0001","SK0001",1)),
                    "STORE_PICKUP","PP0001",null,claimed.userCouponNo(),0L,null,null,null,null,null);
            var order=application.getBean(ai.neargo.shop.trade.service.OrderService.class).create(command,"USE"+f.id());
            assertThat(order.orderNo()).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,claimed.userCouponNo())).isZero();
            var report=payIssues.get(f.scope(),issueNo);
            assertThat(report.getClass().getMethod("usedCount").invoke(report)).isEqualTo(1);
            assertThat(report.getClass().getMethod("usedAmountMinor").invoke(report)).isEqualTo(100L);
            assertThat(coupons.detail(f.scope().entityNo(),f.source().sourceNo()).usedTimes()).isEqualTo(1);
            application.getBean(ai.neargo.shop.trade.service.OrderService.class).cancel(order.payOrderNo(),"return original coupon use");
            var recovered=application.getBean(ai.neargo.shop.paybridge.EntitlementRecoveryService.class).recover("MAIN",
                    ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind.LEGACY_COUPON,0,100);
            assertThat(recovered.failed()).isZero();
            var returned=payIssues.get(f.scope(),issueNo);
            assertThat(returned.usedCount()).isZero();assertThat(returned.usedAmountMinor()).isZero();
            assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,claimed.userCouponNo())).isEqualTo(1);

        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
