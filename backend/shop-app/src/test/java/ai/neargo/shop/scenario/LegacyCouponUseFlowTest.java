package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/** Real pay transactions and existing asset ledger; authoritative core source facts are explicit fixtures. */
@SpringBootTest(properties={"shop.pay.entitlement.use.enabled=true","shop.pay.entitlement.legacy-coupon.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:legacy-coupon-use;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class LegacyCouponUseFlowTest {
    @Autowired LegacyCouponUseService uses;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService lifecycle;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService reconciliation;
    @Autowired PayEntitlementProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean LegacyCouponCheckoutSourcePort sources;
    @MockitoBean EntitlementOwnerRegistryPort owners;
    private final EntitlementContext ctx=new EntitlementContext("MAIN","BUYER","LEGACY","a".repeat(64));
    private record Fixture(CheckoutFact fact,LegacyCouponUsePort.Prepare command,String asset) {}
    private Fixture fixture(int remaining) {
        properties.getEntitlement().getUse().setEnabled(true);
        properties.getEntitlement().getLegacyCoupon().setEnabled(true);
        String id=UUID.randomUUID().toString(),asset="EI"+id,version="EV"+id,checkout="LC"+id,op="LO"+id,user="U"+id;
        Instant from=Instant.now().minusSeconds(100),until=Instant.now().plusSeconds(3600);
        var rule=new EntitlementDefinition("old","ORDER_COUPON","MERCHANT","CNY","CASH",100L,null,null,300L,null,
                "COUNT",remaining,null,new Validity("ABSOLUTE",from,until,null,"GRANTED","ROLLING_DAYS","Asia/Shanghai"),
                new RedemptionScope("ALL",List.of()),List.of("TARGETED"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,entity_no,kind,"
                +"status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,effective_from,effective_until,"
                +"tenant_no,created_at,updated_at,created_by,updated_by) VALUES(?,?,?, ?,?,'M1','ORDER_COUPON','ACTIVE',?,1,?,?,0,1,?,?,'MAIN',"
                +"CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')",asset,version,"G"+id,"K"+id,user,json.writeValueAsString(rule),
                remaining,remaining,java.time.LocalDateTime.ofInstant(from,java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.ofInstant(until,java.time.ZoneOffset.UTC));
        jdbc.update("INSERT INTO ent_ledger(entry_no,instance_no,asset_seq,biz_type,biz_key,units_delta,reserved_delta,after_units,"
                +"after_reserved,occurred_at,tenant_no,created_at,updated_at,created_by,updated_by) VALUES(?,?,1,'GRANT',?, ?,0,?,0,"
                +"CURRENT_TIMESTAMP,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')","EL"+id,asset,"GRANT:"+id,remaining,remaining);
        var origin=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"OLD"+id);
        var fact=new CheckoutFact(1,IntentKind.CHECKOUT,"MAIN",checkout,op,user,"M1",origin,asset,"B1","b".repeat(64),"CNY",
                List.of(new MerchantGroup("S1","M1","S1",101),new MerchantGroup("SX","M2","SX",50),
                        new MerchantGroup("S2","M1","S2",199)),"c".repeat(64),"BUYER",from,until);
        when(sources.resolve(any(),eq(checkout))).thenReturn(fact);
        when(sources.decision(any(),eq(checkout))).thenReturn(Optional.empty());
        when(sources.reversal(any(),eq(checkout))).thenReturn(Optional.empty());
        when(owners.resolve(any(),eq(origin))).thenReturn(
                new OwnerRef(origin,asset,OwnerSystem.PAY,MigrationState.CUTOVER,"B1","b".repeat(64),0));
        return new Fixture(fact,new LegacyCouponUsePort.Prepare(checkout,op,user,"M1"),asset);
    }
    private LegacyCouponUsePort.Complete completion(Fixture f,String no) {
        return new LegacyCouponUsePort.Complete(f.fact().checkoutNo(),f.fact().operationNo(),"M1",no);
    }
    private void decision(Fixture f,String no,Outcome outcome,Long amount) {
        when(sources.decision(any(),eq(f.fact().checkoutNo()))).thenReturn(Optional.of(new Decision(
                f.fact().checkoutNo(),f.fact().operationNo(),no,outcome,outcome==Outcome.COMMIT?"ORDER1":null,
                f.fact().economicHash(),amount,Instant.now())));
    }
    private int count(Fixture f,String column) {
        return jdbc.queryForObject("SELECT "+column+" FROM ent_instance WHERE instance_no=?",Integer.class,f.asset());
    }
    @Test void storeQuoteHoldCommitAndRestoreUseTheExistingLedgerExactlyOnceEvenAfterClosingNewUse() {
        var f=fixture(3);
        assertThat(uses.quote(ctx,f.command()).discountMinor()).isEqualTo(100);
        assertThat(count(f,"reserved_units")).isZero();
        var held=uses.prepare(ctx,f.command());
        assertThat(held.status()).isEqualTo(LegacyCouponUsePort.State.HELD);
        assertThat(held.allocations()).extracting(a->a.discountMinor()).containsExactly(34L,66L);
        assertThat(uses.prepare(ctx,f.command())).isEqualTo(held);
        assertThat(count(f,"reserved_units")).isEqualTo(1);
        var run=reconciliation.check(new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN","M1","OPS",true),
                new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("HOLD",null,java.time.LocalDate.now(),
                        "Asia/Shanghai","",200),"LEGACY-HOLD-"+UUID.randomUUID());
        assertThat(run.diffCount()).isZero();
        properties.getEntitlement().getLegacyCoupon().setEnabled(false);
        jdbc.update("UPDATE ent_instance SET status='FROZEN' WHERE instance_no=?",f.asset());
        assertThat(uses.prepare(ctx,f.command())).isEqualTo(held);
        decision(f,"D1",Outcome.COMMIT,100L);
        assertThat(uses.commit(ctx,completion(f,"D1")).status()).isEqualTo(LegacyCouponUsePort.State.COMMITTED);
        uses.commit(ctx,completion(f,"D1"));
        assertThat(count(f,"remaining_units")).isEqualTo(2);
        assertThat(count(f,"reserved_units")).isZero();
        when(sources.reversal(any(),eq(f.fact().checkoutNo()))).thenReturn(Optional.of(new Reversal(f.fact().checkoutNo(),
                f.fact().operationNo(),"R1","ORDER1","CANCEL1",f.fact().economicHash(),"CNY",100,
                ReverseReason.CANCELLED,Instant.now())));
        assertThat(uses.reverse(ctx,completion(f,"R1")).status()).isEqualTo(LegacyCouponUsePort.State.REVERSED);
        uses.reverse(ctx,completion(f,"R1"));
        assertThat(count(f,"remaining_units")).isEqualTo(3);
        assertThat(count(f,"reserved_units")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ent_instance WHERE instance_no=?",String.class,f.asset())).isEqualTo("FROZEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,f.asset())).isEqualTo(4);
    }
    @Test void exhaustedThenFrozenAndUnfrozenCouponIsUsableAgainAfterOriginalOrderReversal() {
        var f = fixture(1);
        uses.prepare(ctx, f.command());
        decision(f, "D1", Outcome.COMMIT, 100L);
        uses.commit(ctx, completion(f, "D1"));
        var scope = new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN", "M1", "OPS", true);
        var state = lifecycle.state(scope, f.asset());
        var frozen = lifecycle.freeze(scope, f.asset(),
                new ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand(state.revision(), "FREEZE", "review"),
                UUID.randomUUID().toString());
        assertThat(lifecycle.freeze(scope, f.asset(),
                new ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand(frozen.revision(), "UNFREEZE", "review"),
                UUID.randomUUID().toString()).status()).isEqualTo("EXHAUSTED");
        when(sources.reversal(any(), eq(f.fact().checkoutNo()))).thenReturn(Optional.of(new Reversal(
                f.fact().checkoutNo(), f.fact().operationNo(), "R1", "ORDER1", "CANCEL1", f.fact().economicHash(),
                "CNY", 100, ReverseReason.CANCELLED, Instant.now())));
        uses.reverse(ctx, completion(f, "R1"));
        uses.reverse(ctx, completion(f, "R1"));
        assertThat(count(f, "remaining_units")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_instance WHERE instance_no=?", String.class, f.asset()))
                .isEqualTo("ACTIVE");
        var base = f.fact();
        String checkout = "REUSE" + UUID.randomUUID(), operation = "REUSE" + UUID.randomUUID();
        var next = new CheckoutFact(1, IntentKind.CHECKOUT, base.tenantNo(), checkout, operation,
                base.userNo(), base.entityNo(), base.origin(), base.targetInstanceNo(), base.ownerBatchNo(),
                base.ownerPlanHash(), base.currency(), base.groups(), base.economicHash(), base.selectionActorNo(),
                base.createdAt(), base.deadlineAt());
        when(sources.resolve(any(), eq(checkout))).thenReturn(next);
        when(sources.decision(any(), eq(checkout))).thenReturn(Optional.empty());
        when(sources.reversal(any(), eq(checkout))).thenReturn(Optional.empty());
        var command = new LegacyCouponUsePort.Prepare(checkout, operation, base.userNo(), base.entityNo());
        assertThat(uses.quote(ctx, command).discountMinor()).isEqualTo(100);
        assertThat(uses.prepare(ctx, command).status()).isEqualTo(LegacyCouponUsePort.State.HELD);
    }
    @Test void abortBeforeHoldPersistsATombstoneWithoutInventingAUseOrDiscount() {
        var f=fixture(3);
        decision(f,"A1",Outcome.ABORT,null);
        var cancelled=uses.release(ctx,completion(f,"A1"));
        assertThat(cancelled.status()).isEqualTo(LegacyCouponUsePort.State.RELEASED);
        assertThat(cancelled.discountMinor()).isNull();
        assertThat(cancelled.allocations()).isEmpty();
        assertThat(uses.prepare(ctx,f.command())).isEqualTo(cancelled);
        assertThat(count(f,"reserved_units")).isZero();
        assertThat(count(f,"remaining_units")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,f.asset())).isEqualTo(1);
        assertThatThrownBy(()->uses.prepare(ctx,new LegacyCouponUsePort.Prepare(f.fact().checkoutNo(),f.fact().operationNo(),"OTHER","M1")))
                .isInstanceOf(BizException.class);
    }
    @Test void absentOrMismatchingCoreDecisionCannotConsumeTheHeldUnit() {
        var f=fixture(3);
        uses.prepare(ctx,f.command());
        assertThatThrownBy(()->uses.commit(ctx,completion(f,"UNKNOWN"))).isInstanceOf(BizException.class);
        decision(f,"BAD",Outcome.COMMIT,99L);
        assertThatThrownBy(()->uses.commit(ctx,completion(f,"BAD"))).isInstanceOf(BizException.class);
        assertThat(count(f,"remaining_units")).isEqualTo(3);
        assertThat(count(f,"reserved_units")).isEqualTo(1);
        decision(f,"ABORT",Outcome.ABORT,null);
        uses.release(ctx,completion(f,"ABORT"));
        assertThat(count(f,"reserved_units")).isZero();
        assertThat(count(f,"remaining_units")).isEqualTo(3);
    }
    @Test void concurrentCheckoutsCannotReserveTheSameLastUnit() throws Exception {
        var f=fixture(1);
        var base=f.fact(); String checkout="OTHER"+UUID.randomUUID(),op="OP"+UUID.randomUUID();
        var other=new CheckoutFact(1,IntentKind.CHECKOUT,base.tenantNo(),checkout,op,base.userNo(),base.entityNo(),base.origin(),base.targetInstanceNo(),
                base.ownerBatchNo(),base.ownerPlanHash(),base.currency(),base.groups(),base.economicHash(),base.selectionActorNo(),
                base.createdAt(),base.deadlineAt());
        when(sources.resolve(any(),eq(checkout))).thenReturn(other);
        when(sources.decision(any(),eq(checkout))).thenReturn(Optional.empty());
        var command=new LegacyCouponUsePort.Prepare(checkout,op,base.userNo(),"M1");
        var go=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{go.await();try {uses.prepare(ctx,f.command());return true;}catch(BizException expected){return false;}});
            var second=pool.submit(()->{go.await();try {uses.prepare(ctx,command);return true;}catch(BizException expected){return false;}});
            go.countDown();
            int successes=(first.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0)
                    +(second.get(10,java.util.concurrent.TimeUnit.SECONDS)?1:0);
            assertThat(successes).isEqualTo(1);
        }
        assertThat(count(f,"reserved_units")).isEqualTo(1);
        assertThat(count(f,"remaining_units")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_legacy_coupon_operation WHERE instance_no=?",Integer.class,f.asset())).isEqualTo(1);
    }
    @Test void aReadOnlyQuoteSnapshotIsNeverAnAuthorizationToReserveTheCoupon() {
        var f=fixture(3); var b=f.fact();
        var quoteOnly=new CheckoutFact(1,IntentKind.QUOTE_ONLY,b.tenantNo(),b.checkoutNo(),b.operationNo(),b.userNo(),b.entityNo(),
                b.origin(),b.targetInstanceNo(),b.ownerBatchNo(),b.ownerPlanHash(),b.currency(),b.groups(),b.economicHash(),
                b.selectionActorNo(),b.createdAt(),b.deadlineAt());
        when(sources.resolve(any(),eq(b.checkoutNo()))).thenReturn(quoteOnly);
        assertThat(uses.quote(ctx,f.command()).discountMinor()).isEqualTo(100);
        assertThatThrownBy(()->uses.prepare(ctx,f.command())).isInstanceOf(BizException.class);
        assertThat(count(f,"reserved_units")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_legacy_coupon_operation WHERE operation_no=?",
                Integer.class,b.operationNo())).isZero();
    }

}
