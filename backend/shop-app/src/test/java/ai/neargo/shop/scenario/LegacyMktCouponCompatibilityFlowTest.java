package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.paybridge.LegacyCouponMigrationCommitCoordinator;
import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.archive.ArchiveService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mkt-compatibility;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.core.entitlement.migration-watermark-key=mkt-source-watermark-key-over-thirty-two-characters",
        "shop.core.entitlement.migration-commit.enabled=true","shop.pay.entitlement.catalog.enabled=true","shop.pay.entitlement.issue.enabled=true",
        "shop.pay.entitlement.use.enabled=true","shop.pay.entitlement.legacy-coupon.enabled=true",
        "shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","shop.pay.entitlement.migration-snapshot-key-id=MKTTEST"})
@ActiveProfiles("test")
class LegacyMktCouponCompatibilityFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired EntitlementMigrationAppService migration;
    @Autowired LegacyCouponMigrationCommitCoordinator source;
    @Autowired CouponService coupons;
    @Autowired ai.neargo.shop.promotion.service.CouponService promo;
    @Autowired ArchiveService archives;
    @Autowired ai.neargo.shop.marketing.attribution.FissionService fissions;
    @Test void aProvenSharedSourceHasOnlyItsCanonicalConsumerCenterEntry() {
        var f=LegacyCouponMigrationFixture.prepare(jdbc,fences,migration,true);
        source.commit(f.scope(),f.planNo(),"canonical center source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"canonical center"),"I"+f.id());
        promo.setStatus(f.scope().entityNo(),f.source().sourceNo(),"ACTIVE");
        assertThat(promo.center(null)).anyMatch(c->f.source().sourceNo().equals(c.couponNo()));
        assertThat(coupons.center()).noneMatch(c->f.source().sourceNo().equals(c.couponNo()));
    }
    @Test void lateUncooperativeProgramBindingInvalidatesThePreparedSourcePlan() {
        var f=LegacyCouponMigrationFixture.prepareLegacy(jdbc,fences,migration);
        jdbc.update("INSERT INTO mkt_fission_campaign(fission_no,name,reward_type,coupon_no,inviter_count,invitee_count,enabled,tenant_no,created_at,updated_at) VALUES(?,'old program','COUPON',?,1,1,TRUE,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","F"+f.id(),f.source().sourceNo());
        assertThatThrownBy(()->source.commit(f.scope(),f.planNo(),"must not strand old reward","C"+f.id())).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT migration_state FROM mkt_coupon WHERE coupon_no=?",String.class,f.source().sourceNo())).isEqualTo("FROZEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_migration_source WHERE source_template_no=?",Integer.class,f.source().sourceNo())).isZero();
    }
    @Test void oldOpsAndConsumerEntriesUsePayAfterActualLegacyMigration() {
        var f=LegacyCouponMigrationFixture.prepareLegacy(jdbc,fences,migration);String user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        source.commit(f.scope(),f.planNo(),"mkt source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"mkt baseline"),"I"+f.id());
        var view=coupons.opsCoupons(null,true).stream().filter(c->c.couponNo().equals(f.source().sourceNo())).findFirst().orElseThrow();
        assertThat(view.status()).isEqualTo("PAUSED");
        assertThatThrownBy(()->fissions.save(new ai.neargo.shop.marketing.attribution.FissionService.SaveCommand(
                null,"cannot bind frozen source",f.source().sourceNo(),1,1),"OP1")).isInstanceOf(ai.neargo.shop.common.BizException.class);
        coupons.setCouponStatus(f.source().sourceNo(),"ACTIVE","explicit resume","OP1");
        var issued=coupons.issue(f.source().sourceNo(),"SINGLE_USER","actual customer",user,2,"OP1");
        assertThat(issued.count()).isEqualTo(2);assertThat(issued.amount()).isEqualTo(200);
        assertThat(coupons.issues(f.source().sourceNo())).extracting(c->c.issueNo()).contains(issued.issueNo());
        coupons.setBudget(f.source().sourceNo(),3000,"OP1");
        var updated=coupons.saveCoupon(new ai.neargo.shop.marketing.coupon.dto.CouponSaveCmd(view.couponNo(),"future legacy","FULL_CUT",200L,null,null,0L,10,3,3000L,view.validFrom(),view.validTo()),"OP1");
        assertThat(updated.value()).isEqualTo(200);
        var login=new ai.neargo.shop.auth.LoginUser(ai.neargo.shop.auth.Realm.CONSUMER,ai.neargo.auth.store.SubjectKind.USR,user,"Buyer",List.of(),List.of(),null,null);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,List.of()));
        try {
            coupons.receive(f.source().sourceNo());assertThat(coupons.mine()).hasSize(3);
            assertThatThrownBy(()->coupons.receive(f.source().sourceNo())).isInstanceOf(ai.neargo.shop.common.BizException.class);
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        long at=archives.archive(ArchiveService.Kind.COUPON,f.source().sourceNo(),"OP1");
        assertThat(archives.archive(ArchiveService.Kind.COUPON,f.source().sourceNo(),"OP1")).isEqualTo(at);
        assertThat(coupons.opsCoupons(null,false)).noneMatch(c->c.couponNo().equals(f.source().sourceNo()));
        archives.unarchive(ArchiveService.Kind.COUPON,f.source().sourceNo(),"OP1");
        assertThat(coupons.opsCoupons(null,false)).anyMatch(c->c.couponNo().equals(f.source().sourceNo()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_user_coupon WHERE coupon_no=?",Integer.class,f.source().sourceNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT received_count FROM mkt_coupon WHERE coupon_no=?",Integer.class,f.source().sourceNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT face_minor FROM mkt_coupon WHERE coupon_no=?",Long.class,f.source().sourceNo())).isEqualTo(100);
    }
}
