package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.paybridge.LegacyCouponMigrationCommitCoordinator;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.user.service.PersonService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.Cohort;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.paybridge.LegacyCouponRefundCompletedConsumer;
import ai.neargo.shop.paybridge.LegacyCouponCheckoutDecisionConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Cohort.class)
    void merchantCanCreateClaimAndContinueIssuingAnUnusedFamilyAfterCutover(Cohort cohort) {
        boolean multiple = cohort == Cohort.UNUSED_VERSIONED_COUPONS;
        boolean storeScoped = cohort != Cohort.UNUSED_MERCHANT_COUPONS;
        String id = UUID.randomUUID().toString();
        String entity = "M" + id;
        String originalHolder = "U" + id;
        String nextHolder = "U2" + id;
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at)"
                + " VALUES(?,'OWNER','merchant','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        String targetedHolder = "U3" + id;
        for (String holder : List.of(originalHolder, nextHolder, targetedHolder)) {
            jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at)"
                    + " VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", holder);
        }
        long start = System.currentTimeMillis() - 1000;
        String store = "ST-" + id;
        if (storeScoped) StoreOwnerFixture.store(jdbc, entity, store);
        var template = coupons.save(entity, new CouponSaveCmd(null, "会员满减券", "CASH", 100L,
                null, null, 1000L, null, storeScoped ? "STORE" : "ALL", storeScoped ? List.of(store) : List.of(), null, "ABSOLUTE", start,
                start + 86_400_000L, null, "CENTER", "ORDER", 1, 10, 1, 1000L), "OP1");
        String couponNo = template.couponNo();
        enrolled(entity, targetedHolder, true);
        var audience = List.of(new ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem("ALL", null));
        var originalBatch = coupons.issue(entity, couponNo, null, audience, "OP1", id);
        assertThat(originalBatch.issued()).isEqualTo(1);
        var original = coupons.receive(originalHolder, couponNo, id);
        assertThat(jdbc.queryForObject("SELECT template_version_no FROM pmt_user_coupon WHERE user_coupon_no=?",
                String.class, original.userCouponNo())).isNotBlank();

        String revisedHolder = "U4" + id;
        String revisedInstance = null;
        if (multiple) {
            coupons.save(entity, new CouponSaveCmd(couponNo, "新版全店券", "CASH", 200L, null, null, 1000L, null,
                    "ALL", List.of(), null, "ABSOLUTE", start, start + 86_400_000L, null,
                    "CENTER", "ORDER", 1, 10, 1, 2000L), "OP1");
            jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at)"
                    + " VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", revisedHolder);
            revisedInstance = coupons.receive(revisedHolder, couponNo).userCouponNo();
        }

        var identity = new SourceIdentity(SourceSystem.PMT, SourceKind.TEMPLATE, couponNo);
        var ctx = new EntitlementContext("MAIN", "OP1", id, "a".repeat(64));
        var scope = new CatalogScope("MAIN", entity, "OP1", true);
        var draining = fences.transition(ctx, identity, new Command(Action.BEGIN_DRAIN, id, fences.state(ctx, identity).revision(), "first batch", "D" + id));
        var frozen = fences.transition(ctx, identity, new Command(Action.FREEZE, id, draining.revision(), "first batch", "F" + id));
        var evidence = migration.stageManifest(scope, new StageCommand(
                new Query(identity, id, frozen.frozenRevision(), frozen.watermark()), "unused merchant coupons"), "S" + id);
        var plan = migration.plan(scope, new PlanCommand(evidence.evidence().stageNo(), "first batch",
                cohort), "P" + id);
        assertThat(plan.assetCount()).isEqualTo(multiple ? 3 : 2);
        source.commit(scope, plan.planNo(), "first batch", "C" + id);
        migration.importPlan(scope, new ImportCommand(plan.planNo(), "first batch"), "I" + id);
        coupons.setStatus(entity, couponNo, "ACTIVE");
        assertThat(coupons.issue(entity, couponNo, null, audience, "OP1", id).issueNo()).isEqualTo(originalBatch.issueNo());
        assertThatThrownBy(() -> coupons.issue(entity, couponNo, "different", audience, "OP1", id))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(coupons.receive(originalHolder, couponNo, id)).isEqualTo(original);
        var issued = coupons.receive(nextHolder, couponNo);
        assertThat(issued.userCouponNo()).isNotEqualTo(original.userCouponNo());
        if (storeScoped) {
            assertThat(coupons.detail(entity,couponNo).scopeType()).isEqualTo(multiple ? "ALL" : "STORE");
            assertThat(coupons.detail(entity,couponNo).scopeRefs()).isEqualTo(multiple ? List.of() : List.of(store));
            var allowed = new ai.neargo.shop.spi.marketing.CouponPort.MerchantAmount(entity, 1500L, store);
            var other = new ai.neargo.shop.spi.marketing.CouponPort.MerchantAmount(entity, 3000L, "OTHER");
            if (multiple) {
                assertThat(router.allocate(revisedHolder,revisedInstance,List.of(other)).totalDiscount()).isEqualTo(200);
                assertThat(router.allocate(nextHolder,issued.userCouponNo(),List.of(other)).totalDiscount()).isEqualTo(200);
            }
            var limited = multiple ? Map.of(originalHolder, original.userCouponNo())
                    : Map.of(originalHolder, original.userCouponNo(), nextHolder, issued.userCouponNo());
            for (var holder : limited.entrySet()) {
                var accepted = router.allocate(holder.getKey(),holder.getValue(),List.of(allowed, other));
                assertThat(accepted.totalDiscount()).isEqualTo(100);
                assertThat(accepted.shares()).singleElement().satisfies(share -> assertThat(share.storeNo()).isEqualTo(store));
                assertThatThrownBy(() -> router.allocate(holder.getKey(),holder.getValue(),List.of(other)))
                        .isInstanceOf(ai.neargo.shop.common.BizException.class)
                        .hasMessage(ai.neargo.shop.common.ErrorCode.COUPON_NOT_APPLICABLE.name());
            }
        }
        assertThat(coupons.myCoupons(originalHolder)).hasSize(1);
        assertThat(coupons.myCoupons(nextHolder)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE entity_no=? AND user_no=?",
                Integer.class, entity, nextHolder)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_user_coupon WHERE coupon_no=?",
                Integer.class, couponNo)).isEqualTo(multiple ? 3 : 2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void newlyIssuedSingleUseMerchantCouponSurvivesDuplicateWholeRefund(boolean storeScoped) throws Exception {
        String id = UUID.randomUUID().toString();
        String entity = "M0001", user = "U" + id;
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at)"
                + " VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
        long now = System.currentTimeMillis();
        String template = coupons.save(entity, new CouponSaveCmd(null, "首批商家券", "CASH", 100L,
                null, null, 1000L, null, storeScoped ? "STORE" : "ALL", storeScoped ? List.of("ST-M0001") : List.of(), null, "ABSOLUTE", now-1000, now+86400000L,
                null, "CENTER", "ORDER", 1, 10, 1, null), "OP1").couponNo();
        String priorUser = "U-before-" + id;
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at)"
                + " VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", priorUser);
        coupons.receive(priorUser, template, id);
        var root = new SourceIdentity(SourceSystem.PMT, SourceKind.TEMPLATE, template);
        var ctx = new EntitlementContext("MAIN","OP1",id,"a".repeat(64));
        var scope = new CatalogScope("MAIN",entity,"OP1",true);
        var drain = fences.transition(ctx, root, new Command(Action.BEGIN_DRAIN,id,fences.state(ctx,root).revision(),"首批","D"+id));
        var frozen = fences.transition(ctx, root, new Command(Action.FREEZE,id,drain.revision(),"首批","F"+id));
        var stage = migration.stageManifest(scope,new StageCommand(new Query(root,id,frozen.frozenRevision(),frozen.watermark()),"首批"),"S"+id);
        var plan = migration.plan(scope,new PlanCommand(stage.evidence().stageNo(),"首批",storeScoped ? Cohort.UNUSED_STORE_COUPONS : Cohort.UNUSED_MERCHANT_COUPONS),"P"+id);
        source.commit(scope,plan.planNo(),"首批","C"+id);
        migration.importPlan(scope,new ImportCommand(plan.planNo(),"首批"),"I"+id);
        coupons.setStatus(entity,template,"ACTIVE");
        String instance = coupons.receive(user,template,id).userCouponNo();
        var buyer = LoginUser.consumer(user,"Buyer");
        var auth = new UsernamePasswordAuthenticationToken(buyer,null,List.of());
        auth.setDetails(application.getBean(TokenStores.class).of(Realm.CONSUMER).issue(TokenStore.SessionData.of(buyer)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var cmd = new OrderService.CreateOrderCommand(List.of(new OrderService.CreateOrderCommand.Item("G0001","SK0001",1)),
                    "STORE_PICKUP","PP0001",null,instance,0L,null,null,null,null,null);
            var order = application.getBean(OrderService.class).create(cmd,id);
            String orderNo = order.payOrderNo(), sub = order.subOrders().getFirst().orderNo(), sale = "AS"+id;
            assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,instance)).isZero();
            jdbc.update("UPDATE ord_order SET status='PAID' WHERE order_no=?",orderNo);
            jdbc.update("UPDATE ord_sub_order SET status='REFUNDED',refund_progress='FULL',refunded_minor=pay_amount WHERE sub_order_no=?",sub);
            jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,reason,refund_minor,refunded_at,tenant_no,created_at,updated_at)"
                    + " SELECT ?,sub_order_no,order_no,user_no,entity_no,'REFUND_ONLY','REFUNDED','退款完成',pay_amount,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM ord_sub_order WHERE sub_order_no=?",
                    sale,System.currentTimeMillis(),sub);
            var event = new SysOutbox();
            event.setPayload(application.getBean(tools.jackson.databind.ObjectMapper.class).writeValueAsString(
                    new ai.neargo.shop.spi.trade.OrderEvents.AfterSaleRefunded(sale,sub,user,
                            jdbc.queryForObject("SELECT refunded_minor FROM ord_sub_order WHERE sub_order_no=?",Long.class,sub))));
            var refunds = application.getBean(LegacyCouponRefundCompletedConsumer.class);
            refunds.consume(event);
            refunds.consume(event);
            String checkout = jdbc.queryForObject("SELECT checkout_no FROM ord_legacy_coupon_checkout WHERE order_no=?",String.class,orderNo);
            String eventNo = jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND event_type='LEGACY_COUPON_CHECKOUT_DECIDED' ORDER BY id DESC LIMIT 1",String.class,checkout);
            var delivery = application.getBean(SysOutboxMapper.class).findByEventNo(eventNo);
            var consumer = application.getBean(LegacyCouponCheckoutDecisionConsumer.class);
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Void> retry = () -> { start.await(); consumer.consume(delivery); return null; };
                var a = workers.submit(retry);
                var b = workers.submit(retry);
                start.countDown();
                a.get(); b.get();
            }
            var returned = application.getBean(ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal.class)
                    .couponReturn("MAIN",orderNo,entity,"ST-M0001");
            assertThat(returned.sourceType()).isEqualTo("AFTER_SALE");
            assertThat(returned.discountMinor()).isEqualTo(100L);
            assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,instance)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=? AND biz_type='LEGACY_REVERSE'",Integer.class,instance)).isOne();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ent_use_reversal r JOIN ent_usage u ON r.tenant_no=u.tenant_no AND r.usage_no=u.usage_no
                    WHERE u.source_order_no=? AND u.status='REVERSED' AND r.instance_no=? AND r.source_type='AFTER_SALE'
                      AND r.source_no=? AND r.discount_minor=100 AND r.units=1
                    """,Integer.class,orderNo,instance,sale)).isOne();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test void firstBatchRejectsFamiliesWithPriorUse() {
        var history = LegacyCouponMigrationFixture.prepareHistory(jdbc, fences, migration);
        var plan = migration.getPlan(history.scope(), history.planNo());
        assertThatThrownBy(() -> migration.plan(history.scope(), new PlanCommand(plan.stageNo(), "first batch",
                Cohort.UNUSED_MERCHANT_COUPONS), "P2" + history.id()))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
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
        String requestKey="targeted-"+f.id();
        jdbc.update("""
                INSERT INTO mbr_reach_log(reach_no,entity_no,member_no,scene,sent_at,created_at,updated_at)
                SELECT member_no,entity_no,member_no,'COUPON',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM mbr_member WHERE entity_no=?
                """, System.currentTimeMillis(), f.scope().entityNo());
        var filtered = coupons.issue(f.scope().entityNo(), f.source().sourceNo(), null, audience, "OP1", "r"+f.id(), true);
        assertThat(filtered.issued()).isZero();
        assertThat(filtered.skipped()).isEqualTo(3);
        assertThat(coupons.issue(f.scope().entityNo(), f.source().sourceNo(), null, audience, "OP1", "r"+f.id(), true))
                .isEqualTo(filtered);
        assertThatThrownBy(() -> coupons.issue(f.scope().entityNo(), f.source().sourceNo(), null, audience, "OP1", "r"+f.id(), false))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        var issued=coupons.issue(f.scope().entityNo(),f.source().sourceNo(),null,audience,"OP1",requestKey);
        assertThat(coupons.issue(f.scope().entityNo(),f.source().sourceNo(),null,audience,"OP1",requestKey))
                .isEqualTo(issued);
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
        var claimed=coupons.receive(user,f.source().sourceNo(),"claim");
        assertThat(coupons.receive(user,f.source().sourceNo(),"claim").userCouponNo()).isEqualTo(claimed.userCouponNo());
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
