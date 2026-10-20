package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.*;
import ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Core journal SQL/transaction tests; pricing and prior migration ownership are explicit fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"shop.services.internal-token=journal-test","shop.core.entitlement.legacy-coupon.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:legacy-checkout-journal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class LegacyCouponCheckoutJournalFlowTest {
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired LegacyCouponCheckoutJournal journal;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean LegacyCouponIdentityPort identities;
    private final EntitlementContext ctx=new EntitlementContext("MAIN","BUYER","CHECKOUT","a".repeat(64));
    private LegacyCouponCheckoutJournal.Stage command(IntentKind kind) {
        String id=UUID.randomUUID().toString();
        var origin=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"PU"+id);
        when(identities.ownedOrigins(eq(ctx),eq("BUYER"),eq(origin.sourceNo()))).thenReturn(List.of(
                new LegacyCouponIdentityPort.Origin(origin,"T1","M1","MERCHANT","COMMIT_DECIDED","B1","b".repeat(64),true,false,true)));
        return new LegacyCouponCheckoutJournal.Stage(kind,"LC"+id,"LO"+id,"BUYER","M1",origin,"EI"+id,
                "B1","b".repeat(64),"CNY",List.of(new MerchantGroup("S1","M1","S1",500)),"c".repeat(64));
    }
    private LegacyCouponUsePort.Operation held(CheckoutFact f) {
        return new LegacyCouponUsePort.Operation(f.checkoutNo(),f.operationNo(),f.targetInstanceNo(),f.entityNo(),"CNY",f.economicHash(),
                LegacyCouponUsePort.State.HELD,100L,List.of(new LegacyCouponUsePort.Allocation("S1","M1","S1",100)));
    }
    private String order() {
        String no="SO"+UUID.randomUUID();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,goods_amount,freight_amount,discount_amount,currency,tenant_no,created_at,updated_at) "
                +"VALUES(?,'BUYER','WAIT_PAY',400,500,0,100,'CNY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",no);
        return no;
    }
    @Test void immutableIntentReplaysWithoutReReadingMutableSourceAndAbortIsDurable() {
        var cmd=command(IntentKind.CHECKOUT);var fact=journal.stage(ctx,cmd);
        reset(identities);
        assertThat(journal.stage(ctx,cmd)).isEqualTo(fact);
        verifyNoInteractions(identities);
        assertThat(journal.decision(ctx,fact.checkoutNo())).isEmpty();
        var cancelled=journal.abort(ctx,fact.checkoutNo());
        assertThat(cancelled.outcome()).isEqualTo(Outcome.ABORT);assertThat(cancelled.discountMinor()).isNull();
        assertThat(journal.abort(ctx,fact.checkoutNo())).isEqualTo(cancelled);
        assertThat(journal.resolve(ctx,fact.checkoutNo())).isEqualTo(fact);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            journal.bind(ctx,fact.checkoutNo(),"BUYER",cmd.commandHash(),fact.groups(),held(fact));return null;
        })).isInstanceOf(BizException.class);
    }
    @Test void realOrderAndCommitShareOneTransactionAndRollbackCannotLeaveACommit() {
        var cmd=command(IntentKind.CHECKOUT);var fact=journal.stage(ctx,cmd);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            journal.bind(ctx,fact.checkoutNo(),"BUYER",cmd.commandHash(),fact.groups(),held(fact));
            journal.commit(ctx,fact.checkoutNo(),order(),held(fact));throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(journal.decision(ctx,fact.checkoutNo())).isEmpty();
        var result=new TransactionTemplate(transactions).execute(status->{
            journal.bind(ctx,fact.checkoutNo(),"BUYER",cmd.commandHash(),fact.groups(),held(fact));
            return journal.commit(ctx,fact.checkoutNo(),order(),held(fact));
        });
        assertThat(result.outcome()).isEqualTo(Outcome.COMMIT);assertThat(result.discountMinor()).isEqualTo(100);
        assertThat(journal.decision(ctx,fact.checkoutNo())).contains(result);
        assertThatThrownBy(()->journal.abort(ctx,fact.checkoutNo())).isInstanceOf(BizException.class);
    }
    @Test void quoteOnlyAndForeignOrChangedFactsCannotAuthorizeOrderCreation() {
        var cmd=command(IntentKind.QUOTE_ONLY);var fact=journal.stage(ctx,cmd);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            journal.bind(ctx,fact.checkoutNo(),"BUYER",cmd.commandHash(),fact.groups(),held(fact));return null;
        })).isInstanceOf(BizException.class);
        var other=new EntitlementContext("OTHER","BUYER","CHECKOUT","a".repeat(64));
        assertThatThrownBy(()->journal.resolve(other,fact.checkoutNo())).isInstanceOf(BizException.class);
        var realCmd=command(IntentKind.CHECKOUT);var real=journal.stage(ctx,realCmd);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            journal.bind(ctx,real.checkoutNo(),"BUYER",realCmd.commandHash(),
                    List.of(new MerchantGroup("S1","M1","S1",501)),held(real));return null;
        })).isInstanceOf(BizException.class);
        assertThatThrownBy(()->journal.commit(ctx,real.checkoutNo(),"MISSING",held(real)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->
                journal.commit(ctx,real.checkoutNo(),order(),held(real)))).isInstanceOf(BizException.class);
    }
    @Test void unprovedMigrationSourceCannotProduceACheckoutIntent() {
        var cmd=command(IntentKind.CHECKOUT);
        when(identities.ownedOrigins(ctx,"BUYER",cmd.origin().sourceNo())).thenReturn(List.of());
        assertThatThrownBy(()->journal.stage(ctx,cmd)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_legacy_coupon_checkout WHERE checkout_no=?",Integer.class,cmd.checkoutNo())).isZero();
    }
    @Test void actualPersistedSourceCrossesTypedAuthenticatedHttpWithExplicitUndecidedEnvelope() {
        var cmd=command(IntentKind.CHECKOUT);var fact=journal.stage(ctx,cmd);
        var locator=new ai.neargo.shop.svc.ConfigServiceLocator();
        locator.getTargets().put(ai.neargo.shop.svc.ServiceName.PLATFORM,"http://127.0.0.1:"+port);
        var api=new ai.neargo.shop.svc.InternalHttp(locator,"journal-test").client(
                ai.neargo.shop.svc.ServiceName.PLATFORM,CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        var query=new EntitlementCoreInternal.LegacyCheckoutQuery(ctx,fact.checkoutNo());
        assertThat(api.legacyCheckoutSource(query)).isEqualTo(fact);
        assertThat(api.legacyCheckoutDecision(query)).isNotNull();
        assertThat(api.legacyCheckoutDecision(query).decision()).isNull();
        assertThat(api.legacyCheckoutReversal(query).reversal()).isNull();
        var abort=journal.abort(ctx,fact.checkoutNo());
        assertThat(api.legacyCheckoutDecision(query).decision()).isEqualTo(abort);
    }

    @Test void orderCouponDetailsNeverCopyTheMerchantTotalIntoEachStore() {
        var base=command(IntentKind.CHECKOUT);
        var cmd=new LegacyCouponCheckoutJournal.Stage(base.intentKind(),base.checkoutNo(),base.operationNo(),base.userNo(),base.entityNo(),
                base.origin(),base.targetInstanceNo(),base.ownerBatchNo(),base.ownerPlanHash(),base.currency(),
                List.of(new MerchantGroup("S1","M1","S1",100),new MerchantGroup("S2","M1","S2",200)),base.commandHash());
        var fact=journal.stage(ctx,cmd);
        var held=new LegacyCouponUsePort.Operation(fact.checkoutNo(),fact.operationNo(),fact.targetInstanceNo(),"M1","CNY",fact.economicHash(),
                LegacyCouponUsePort.State.HELD,100L,List.of(new LegacyCouponUsePort.Allocation("S1","M1","S1",34),
                new LegacyCouponUsePort.Allocation("S2","M1","S2",66)));
        String root=new TransactionTemplate(transactions).execute(tx->{
            journal.bind(ctx,fact.checkoutNo(),"BUYER",cmd.commandHash(),fact.groups(),held);
            String no=order();
            for(int n=1;n<=2;n++) jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,goods_amount,discount_amount,pay_amount,tenant_no,created_at,updated_at) "
                    +"VALUES(?,?,'BUYER','M1',?,'WAIT_PAY','SERVICE',200,100,100,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",no+"-"+n,no,"S"+n);
            journal.commit(ctx,fact.checkoutNo(),no,held);return no;
        });
        assertThat(journal.discountLines("MAIN",root+"-1")).extracting(l->l.amountMinor()).containsExactly(34L);
        assertThat(journal.discountLines("MAIN",root+"-2")).extracting(l->l.amountMinor()).containsExactly(66L);
        assertThat(journal.discountLines("OTHER",root+"-1")).isEmpty();
    }

}
