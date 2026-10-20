package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Action;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService.Command;
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"shop.core.entitlement.migration-watermark-key=source-migration-test-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:legacy-source-fence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class LegacyCouponSourceFenceFlowTest {
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired CouponService coupons;
    @Autowired ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort identities;
    @Autowired CouponAllocService allocations;
    @Autowired ai.neargo.shop.marketing.coupon.CouponService legacy;
    @Autowired JdbcTemplate jdbc;
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    @Autowired ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort snapshots;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("transactionManager")
    org.springframework.transaction.PlatformTransactionManager coreTx;

    @Test void frozenSnapshotRevalidatesOriginalFactsAndRedactsInternalLegacyCode() throws Exception {
        String coupon=id(), instance=id(), secret="OLD-"+id().substring(0,24);
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,tenant_no,"
                + "created_at,updated_at) VALUES(?,'export','CASH',100,'MERCHANT','M1','MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP)",coupon);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                + "redeem_code,received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,?,1,"
                + "1800000000000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",instance,coupon,secret);
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,coupon);
        var ctx=new EntitlementContext("MAIN","MIGRATION",id(),"c".repeat(64));
        String batch=id();
        var draining=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"export rehearsal",id()));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,draining.revision(),"export rehearsal",id()));
        var query=new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(source,batch,
                frozen.frozenRevision(),frozen.watermark());
        var snapshot=snapshots.exportFrozen(ctx,query);
        assertThat(snapshot.watermark()).isEqualTo(frozen.watermark());
        assertThat(snapshot.contentHash()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        snapshot.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(snapshot.toString()).doesNotContain(secret,instance);
        assertThat(snapshot.payload()).contains(secret,instance);
        assertThat(json.readTree(snapshot.payload()).get(4).get(0).path("timesUsed").asInt()).isZero();
        assertThat(snapshots.exportFrozen(ctx,query)).isEqualTo(snapshot);
        assertThatThrownBy(() -> snapshots.exportFrozen(ctx,new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(
                source,id(),frozen.frozenRevision(),frozen.watermark()))).isInstanceOf(BizException.class);
        // Direct SQL represents an uncooperative source writer; it must invalidate the proof.
        var outer=new org.springframework.transaction.support.TransactionTemplate(coreTx);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            outer.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT times_used FROM pmt_user_coupon WHERE user_coupon_no=?",
                        Integer.class,instance)).isZero(); // Establish the caller's older consistent-read snapshot.
                java.util.concurrent.CompletableFuture.runAsync(() ->
                        jdbc.update("UPDATE pmt_user_coupon SET times_used=1 WHERE user_coupon_no=?",instance)).join();
                assertThatThrownBy(() -> snapshots.exportFrozen(ctx,query)).isInstanceOf(BizException.class);
            });
        } finally { jdbc.update("UPDATE pmt_user_coupon SET times_used=0 WHERE user_coupon_no=?",instance); }
        fences.transition(ctx,source,new Command(Action.ABORT,batch,frozen.revision(),"abort rehearsal",id()));
        assertThatThrownBy(() -> snapshots.exportFrozen(ctx,query)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_owner_registry",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
    }

    @Autowired ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort manifests;
    @Test void sourceFamilyManifestPreservesDeletedInstancesIssuanceHistoryAndScope() {
        String template=id(),visible=id(),hidden=id(),batch=id();
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,tenant_no,"
                + "created_at,updated_at) VALUES(?,'manifest','CASH',100,'MERCHANT','M1','MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP)",template);
        for(String no:java.util.List.of(visible,hidden)) {
            jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                    + "received_at,expire_at,deleted,tenant_no,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,1,"
                    + "1800000000000,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",no,template,no.equals(hidden)?1:0);
        }
        jdbc.update("INSERT INTO pmt_coupon_issue(issue_no,coupon_no,issue_mode,issued_at,deleted,tenant_no,created_at,"
                + "updated_at) VALUES(?,?,'TARGETED',1,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(),template);
        jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,amount_minor,applied_at,deleted,tenant_no,"
                + "created_at,updated_at) VALUES(?,'COUPON',?,'U1',100,1,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(),hidden);
        jdbc.update("INSERT INTO pmt_coupon_scope(coupon_no,scope_type,ref_no,deleted,tenant_no,created_at,updated_at)"
                + " VALUES(?,'STORE','HISTORICAL-STORE',1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template);
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,template);
        var ctx=new EntitlementContext("MAIN","MIGRATION",id(),"a".repeat(64));
        var drain=fences.transition(ctx,source,new Command(Action.BEGIN_DRAIN,batch,0,"full evidence",id()));
        var frozen=fences.transition(ctx,source,new Command(Action.FREEZE,batch,drain.revision(),"full evidence",id()));
        var q=new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(source,batch,
                frozen.frozenRevision(),frozen.watermark());
        var exported=manifests.exportManifest(ctx,q);
        var body=json.readTree(exported.payload());
        assertThat(body.path("manifestVersion").asInt()).isEqualTo(2);
        var visibleOrigin=identities.ownedOrigins(ctx,"U1",visible).getFirst();
        assertThat(visibleOrigin.source().sourceSystem()).isEqualTo(SourceSystem.PMT);
        assertThat(visibleOrigin.rootState()).isEqualTo("FROZEN");
        assertThat(visibleOrigin.rootPresent()).isTrue();
        assertThat(visibleOrigin.deleted()).isFalse();
        assertThat(visibleOrigin.cutoverProofHash()).isNull();
        assertThat(identities.ownedOrigins(ctx,"U1",hidden).getFirst().deleted()).isTrue();
        assertThat(identities.ownedOrigins(ctx,"OTHER",visible)).isEmpty();
        assertThat(identities.ownedOrigins(new EntitlementContext("OTHER","MIGRATION",id(),"a".repeat(64)),
                "U1",visible)).isEmpty();

        assertThat(body.path("assessmentFacts").path("version").asInt()).isEqualTo(1);
        assertThat(body.path("assessmentFacts").path("peerTemplateCount").asLong(-1)).isZero();
        assertThat(body.path("assessmentFacts").path("peerInstanceCount").asLong(-1)).isZero();
        assertThat(body.path("historyAvailable").asBoolean()).isTrue();
        assertThat(body.path("instances").size()).isEqualTo(2);
        assertThat(body.path("issues").get(0).path("deleted").asInt()).isEqualTo(1);
        assertThat(body.path("applications").get(0).path("deleted").asInt()).isEqualTo(1);
        assertThat(body.path("scopes").get(0).path("deleted").asInt()).isEqualTo(1);
        assertThat(manifests.exportManifest(ctx,q)).isEqualTo(exported);
        jdbc.update("UPDATE pmt_user_coupon SET order_no=? WHERE user_coupon_no=?",id(),hidden);
        assertThatThrownBy(() -> manifests.exportManifest(ctx,q)).isInstanceOf(BizException.class);
        jdbc.update("UPDATE pmt_user_coupon SET order_no=NULL WHERE user_coupon_no=?",hidden);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
        fences.transition(ctx,source,new Command(Action.ABORT,batch,frozen.revision(),"rehearsal ends",id()));
        assertThatThrownBy(() -> manifests.exportManifest(ctx,q)).isInstanceOf(BizException.class);
    }

    @Test void drainingBlocksNewWritesButOriginalReleaseCompletesBeforeFreezeAndAbortDoesNotResurrectBatch() {
        String coupon = id(), userCoupon = id(), user = id(), entity = id(), order = id();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,scope_type,"
                + "validity_mode,start_at,end_at,issue_mode,redeem_mode,times_total,total_count,received_count,tenant_no,"
                + "created_at,updated_at) VALUES(?,'old coupon','CASH',100,'MERCHANT',?,'ALL','ABSOLUTE',?,?,'CENTER',"
                + "'ORDER',1,100,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",coupon,entity,now-1000,now+3600000);
        // An unknown original binding must block freezing, rather than being assumed cancelled by elapsed time.
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                + "order_no,received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,?,?,'USED',1,?,?,?,"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",userCoupon,coupon,user,entity,order,now,now+3600000);
        var ctx = new EntitlementContext("MAIN", "MIGRATION", "BATCH", "a".repeat(64));
        var source = new SourceIdentity(SourceSystem.PMT, SourceKind.TEMPLATE, coupon);
        String batch = id();
        var begin = new Command(Action.BEGIN_DRAIN, batch, 0, "Offline migration rehearsal", id());
        var draining = fences.transition(ctx, source, begin);
        assertThat(draining.state()).isEqualTo("DRAINING");
        assertThat(fences.transition(ctx, source, begin)).isEqualTo(draining);
        assertThatThrownBy(() -> coupons.receive(id(), coupon)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> coupons.setStatus(entity, coupon, "PAUSED")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fences.transition(ctx, source,
                new Command(Action.FREEZE, batch, draining.revision(), "Must wait for original order", id())))
                .isInstanceOf(RuntimeException.class);
        allocations.release(order);
        assertThat(jdbc.queryForObject("SELECT times_used FROM pmt_user_coupon WHERE user_coupon_no=?",
                Integer.class,userCoupon)).isZero();
        var frozen = fences.transition(ctx, source,
                new Command(Action.FREEZE, batch, draining.revision(), "Original binding drained", id()));
        assertThat(frozen.state()).isEqualTo("FROZEN");
        assertThat(frozen.watermark()).matches("[a-f0-9]{64}");
        var exported=snapshots.exportFrozen(ctx,new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(
                source,batch,frozen.frozenRevision(),frozen.watermark()));
        assertThat(exported.source().sourceSystem()).isEqualTo(SourceSystem.PMT);
        assertThat(exported.payload()).contains("LEGACY_SOURCE_V1");
        assertThat(json.readTree(exported.payload()).get(4).size()).isEqualTo(1);
        assertThat(frozen.frozenRevision()).isEqualTo(frozen.revision());
        assertThatThrownBy(() -> coupons.receive(id(), coupon)).isInstanceOf(RuntimeException.class);
        var aborted = fences.transition(ctx, source,
                new Command(Action.ABORT, batch, frozen.revision(), "No target import decision", id()));
        assertThat(aborted.state()).isEqualTo("ABORT_DECIDED");
        fences.transition(ctx, source, begin);
        assertThat(fences.state(ctx, source).state()).isEqualTo("ABORT_DECIDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_owner_registry",Integer.class)).isZero();
    }
    @Test void legacyMktRootAlsoBlocksIssuanceAndCanAbortWithoutInventingANewOwner() {
        String coupon = id();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,total_count,start_at,"
                + "end_at,tenant_no,created_at,updated_at) VALUES(?,'legacy','FULL_CUT',100,'MERCHANT','M1',100,?,?,"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", coupon, now - 1000, now + 3600000);
        var ctx = new EntitlementContext("MAIN", "MIGRATION", "MKT", "b".repeat(64));
        var source = new SourceIdentity(SourceSystem.MKT_LEGACY, SourceKind.TEMPLATE, coupon);
        String batch = id();
        var draining = fences.transition(ctx, source, new Command(Action.BEGIN_DRAIN, batch, 0, "Mkt rehearsal", id()));
        assertThatThrownBy(() -> legacy.grantTo(id(), coupon)).isInstanceOf(RuntimeException.class);
        var frozen = fences.transition(ctx, source, new Command(Action.FREEZE, batch, draining.revision(), "Empty batch", id()));
        assertThat(frozen.watermark()).matches("[a-f0-9]{64}");
        var exported=snapshots.exportFrozen(ctx,new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(
                source,batch,frozen.frozenRevision(),frozen.watermark()));
        assertThat(exported.source().sourceSystem()).isEqualTo(SourceSystem.MKT_LEGACY);
        var oldManifest=manifests.exportManifest(ctx,new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(
                source,batch,frozen.frozenRevision(),frozen.watermark()));
        assertThat(json.readTree(oldManifest.payload()).path("historyAvailable").asBoolean()).isFalse();
        assertThat(exported.payload()).contains("LEGACY_SOURCE_V1");
        assertThat(json.readTree(exported.payload()).get(4).size()).isZero();
        fences.transition(ctx, source, new Command(Action.ABORT, batch, frozen.revision(), "Source remains authoritative", id()));
        legacy.grantTo(id(), coupon);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_user_coupon WHERE coupon_no=?", Integer.class, coupon))
                .isEqualTo(1);
    }
    @Test void historicalApplyCannotBeReversedAfterFreezeEvenWhenTheInstanceHasNoCurrentOrderPointer() {
        String coupon = id(), instance = id(), order = id(), apply = id();
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,tenant_no,"
                + "created_at,updated_at) VALUES(?,'history','CASH',100,'MERCHANT','M1','MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP)", coupon);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                + "received_at,expire_at,tenant_no,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,1,"
                + "1800000000000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", instance, coupon);
        jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,entity_no,order_no,amount_minor,"
                + "applied_at,tenant_no,created_at,updated_at) VALUES(?,'COUPON',?,'U1','M1',?,100,1,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", apply, instance, order);
        var ctx = new EntitlementContext("MAIN", "MIGRATION", "HISTORY", "c".repeat(64));
        var source = new SourceIdentity(SourceSystem.PMT, SourceKind.TEMPLATE, coupon);
        String batch = id();
        var draining = fences.transition(ctx, source, new Command(Action.BEGIN_DRAIN, batch, 0, "Check history", id()));
        assertThatThrownBy(() -> fences.transition(ctx, source,
                new Command(Action.FREEZE, batch, draining.revision(), "Unknown historical order is not drained", id())))
                .isInstanceOf(RuntimeException.class);
        // Isolate the forbidden-write invariant; this SQL fixture is not an accepted source commit or cutover.
        jdbc.update("UPDATE pmt_coupon SET migration_state='FROZEN',migration_watermark=?,migration_frozen_revision=1 "
                + "WHERE coupon_no=?", "a".repeat(64), coupon);
        assertThatThrownBy(() -> allocations.release(order)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE apply_no=? AND reverted_at IS NULL",
                Integer.class, apply)).isEqualTo(1);
    }
    @Test void unavailableMigrationKeyCannotBeginAFreezeOrCreateACommandAudit() {
        Object target = AopTestUtils.getUltimateTargetObject(fences);
        Object original = ReflectionTestUtils.getField(target, "watermarkKey");
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM pmt_command_dedup", Integer.class);
        try {
            ReflectionTestUtils.setField(target, "watermarkKey", "");
            var context = new EntitlementContext("MAIN", "MIGRATION", "NO_KEY", "d".repeat(64));
            var source = new SourceIdentity(SourceSystem.PMT, SourceKind.TEMPLATE, id());
            assertThatThrownBy(() -> fences.transition(context, source,
                    new Command(Action.BEGIN_DRAIN, id(), 0, "Key unavailable", id())))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_command_dedup", Integer.class)).isEqualTo(before);
        } finally { ReflectionTestUtils.setField(target, "watermarkKey", original); }
    }
    @Test void ownedOrphanIsReportedAsUnresolvedRatherThanInventedAsANativePayAsset() {
        String instance=id(),missing=id();
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,received_at,expire_at,tenant_no,"
                +"created_at,updated_at) VALUES(?,?,'ORPHAN-USER','M1','UNUSED',1,1800000000000,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                instance,missing);
        var result=identities.ownedOrigins(new EntitlementContext("MAIN","MIGRATION",id(),"a".repeat(64)),
                "ORPHAN-USER",instance);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rootPresent()).isFalse();
        assertThat(result.getFirst().rootState()).isEqualTo("UNRESOLVED");
        assertThat(result.getFirst().source().sourceSystem()).isEqualTo(SourceSystem.PMT);
    }

}
