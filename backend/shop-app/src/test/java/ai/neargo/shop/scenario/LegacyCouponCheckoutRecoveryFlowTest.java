package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.paybridge.EntitlementRecoveryService;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.*;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.trade.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** Actual core/pay recovery; a controlled core clock advances the authoritative checkout validity window. */
@SpringBootTest(properties={"shop.core.entitlement.legacy-coupon.enabled=true","shop.pay.entitlement.use.enabled=true",
        "shop.pay.entitlement.legacy-coupon.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:legacy-checkout-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test") @Import(LegacyCouponCheckoutRecoveryFlowTest.Time.class)
class LegacyCouponCheckoutRecoveryFlowTest {
    static class MutableClock extends Clock {
        Instant now=Instant.now();
        void reset(){now=Instant.now();} void advance(){now=now.plusSeconds(960);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    @TestConfiguration static class Time {
        @Bean("legacyCouponCheckoutClock") MutableClock legacyClock(){return new MutableClock();}
    }
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EntitlementRecoveryService recovery;
    @Autowired LegacyCouponCheckoutJournal journal;
    @Autowired LegacyCouponOrderPricingService pricing;
    @Autowired OrderService orders;
    @Autowired LegacyCouponBoundOrderService boundOrders;
    @Autowired ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties properties;
    @MockitoBean LegacyCouponIdentityPort identities;
    @MockitoBean EntitlementOwnerRegistryPort owners;
    @MockitoSpyBean LegacyCouponUsePort uses;
    private String user;
    @BeforeEach void setup() {
        clock.reset();properties.getEntitlement().getUse().setEnabled(true);properties.getEntitlement().getLegacyCoupon().setEnabled(true);
        user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        var login=new LoginUser(Realm.CONSUMER,SubjectKind.USR,user,"Buyer",List.of(),List.of(),null,null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,List.of()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private OrderService.CreateOrderCommand command() {
        return new OrderService.CreateOrderCommand(List.of(new OrderService.CreateOrderCommand.Item("G0001","SK0001",1)),
                "STORE_PICKUP","PP0001",null,"OLD-"+user,0L,null,null,null,null,null);
    }
    private EntitlementContext context(){return new EntitlementContext("MAIN",user,"RECOVER","a".repeat(64));}
    private CheckoutFact stage(String asset,OrderService.CreateOrderCommand cmd) {
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,cmd.couponNo());
        var priced=pricing.snapshot(user,cmd);String id=UUID.randomUUID().toString();
        return journal.stage(context(),new LegacyCouponCheckoutJournal.Stage(IntentKind.CHECKOUT,"LC"+id,"LO"+id,user,
                "M0001",source,asset,"B1","b".repeat(64),priced.currency(),priced.groups(),priced.commandHash()));
    }
    private void closeNewUse(){properties.getEntitlement().getUse().setEnabled(false);properties.getEntitlement().getLegacyCoupon().setEnabled(false);}
    @Test void aCrashedCheckoutWithAHoldIsCancelledByCoreOnlyAfterItsOwnValidityWindow() {
        var cmd=command();String asset=LegacyCouponFixtureSupport.asset(jdbc,json,identities,owners,user,cmd);var fact=stage(asset,cmd);
        var held=uses.prepare(context(),new LegacyCouponUsePort.Prepare(fact.checkoutNo(),fact.operationNo(),user,"M0001"));
        var originalBinding=journal.bindingFor(context(),fact.checkoutNo(),held);
        assertThat(recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100).scanned()).isZero();
        closeNewUse();clock.advance();var result=recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100);
        assertThat(result.completed()).isEqualTo(1);assertThat(result.failed()).isZero();
        assertThat(journal.decision(context(),fact.checkoutNo()).orElseThrow().outcome()).isEqualTo(Outcome.ABORT);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT pay_state FROM ord_legacy_coupon_checkout WHERE checkout_no=?",String.class,fact.checkoutNo())).isEqualTo("RELEASED");
        assertThat(recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100).scanned()).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_by FROM ord_legacy_coupon_checkout WHERE checkout_no=?",String.class,fact.checkoutNo())).isEqualTo("LEGACY_RECOVERY");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ord_legacy_coupon_checkout WHERE checkout_no=?",LocalDateTime.class,fact.checkoutNo()))
                .isEqualTo(LocalDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),ZoneOffset.UTC));
        assertThatThrownBy(()->boundOrders.createBound(user,cmd,"LATE"+UUID.randomUUID(),null,originalBinding))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_no=?",Integer.class,user)).isZero();
    }
    @Test void aCrashedCheckoutBeforeAnyHoldProducesOnlyANullAmountCancellationTombstone() {
        var cmd=command();String asset=LegacyCouponFixtureSupport.asset(jdbc,json,identities,owners,user,cmd);var fact=stage(asset,cmd);
        jdbc.update("UPDATE ord_legacy_coupon_checkout SET recovery_due_at=NULL WHERE checkout_no=?",fact.checkoutNo());
        var upgrade=recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100);
        assertThat(upgrade.deferred()).isEqualTo(1);assertThat(journal.decision(context(),fact.checkoutNo())).isEmpty();
        closeNewUse();clock.advance();assertThat(recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100).completed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ever_held FROM ent_legacy_coupon_operation WHERE checkout_no=?",Integer.class,fact.checkoutNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT discount_minor FROM ent_legacy_coupon_operation WHERE checkout_no=?",Long.class,fact.checkoutNo())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(1);
    }
    @Test void aLostCommitReplyIsRecoveredByTheSameBoundedQueueRatherThanANewCharge() {
        var cmd=command();String asset=LegacyCouponFixtureSupport.asset(jdbc,json,identities,owners,user,cmd);
        doThrow(ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).when(uses).commit(any(),any());
        assertThatThrownBy(()->orders.create(cmd,"LOST"+UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        doCallRealMethod().when(uses).commit(any(),any());closeNewUse();
        var result=recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100);
        assertThat(result.completed()).isEqualTo(1);assertThat(result.failed()).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(3);
        assertThat(recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100).scanned()).isZero();
    }
    @Test void anOldCommitAcknowledgmentCannotClearAConcurrentCancellationDecision() {
        var cmd=command();String asset=LegacyCouponFixtureSupport.asset(jdbc,json,identities,owners,user,cmd);
        doAnswer(invocation->{
            var result=(LegacyCouponUsePort.Operation)invocation.callRealMethod();
            var command=(LegacyCouponUsePort.Complete)invocation.getArgument(1);
            orders.cancel(journal.decision(context(),command.checkoutNo()).orElseThrow().orderNo(),"cancelled before ack");
            return result;
        }).when(uses).commit(any(),any());
        assertThatThrownBy(()->orders.create(cmd,"RACE"+UUID.randomUUID())).isInstanceOf(ai.neargo.shop.common.BizException.class);
        doCallRealMethod().when(uses).commit(any(),any());closeNewUse();
        assertThat(jdbc.queryForObject("SELECT pay_state FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user)).isEqualTo("PENDING");
        assertThat(recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100).completed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pay_state FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(4);
    }

    @Test void walletSnapshotNeverReportsReservedUnitsAsConsumedAndRemainsReadableWhenUseCloses() {
        var cmd=command();String asset=LegacyCouponFixtureSupport.asset(jdbc,json,identities,owners,user,cmd);
        var first=stage(asset,cmd);var second=stage(asset,cmd);
        uses.prepare(context(),new LegacyCouponUsePort.Prepare(first.checkoutNo(),first.operationNo(),user,"M0001"));
        uses.prepare(context(),new LegacyCouponUsePort.Prepare(second.checkoutNo(),second.operationNo(),user,"M0001"));
        var owned=new LegacyCouponUsePort.Owned(user,"M0001",first.origin(),asset,"B1","b".repeat(64));
        var held=uses.snapshot(context(),owned);
        assertThat(held.totalUnits()).isEqualTo(2);assertThat(held.remainingUnits()).isEqualTo(2);assertThat(held.reservedUnits()).isEqualTo(2);
        assertThat(held.usableNow()).isFalse();assertThat(held.lastUsedAt()).isNull();
        closeNewUse();clock.advance();assertThat(recovery.recover("MAIN",Kind.LEGACY_COUPON,0,100).completed()).isEqualTo(2);
        var released=uses.snapshot(context(),owned);
        assertThat(released.remainingUnits()).isEqualTo(2);assertThat(released.reservedUnits()).isZero();assertThat(released.usableNow()).isFalse();
    }

}
