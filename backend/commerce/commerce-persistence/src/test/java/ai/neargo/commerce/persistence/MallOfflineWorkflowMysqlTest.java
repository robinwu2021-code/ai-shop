package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.offline.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;

class MallOfflineWorkflowMysqlTest {
    @Test
    void declarationAndDeclineNeverCreatePaymentOrRightsOrReleaseStoreHold() {
        try (var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest(); var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            var api=service(db,f);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var ticket=api.reserveEvidence(MallOrderConcurrencyMysqlTest.BUYER,q,"BUYER_RECEIPT");
            var claim=new OfflinePurchaseFacade.ClaimCommand(q,"claim",new Money("CNY",8800),Instant.ofEpochMilli(3000),"claimed-bank-ref",List.of(ticket.assetKey()));
            assertEquals("SUBMITTED",api.submit(MallOrderConcurrencyMysqlTest.BUYER,claim).status());
            assertEquals("DECLINED",api.decline(f.mall().c.ops,new OfflinePurchaseFacade.DeclineCommand(q,"claim","illegible")).status());
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_payment_ref WHERE status='SUCCEEDED'",Integer.class));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_fulfillment_task WHERE task_type='DIGITAL'",Integer.class));
            assertEquals(f.order().orderNo(),db.jdbc.queryForObject("SELECT pending_change_order_no FROM mch_subscription_account",String.class));
            assertThrows(MallException.class,()->api.submit(new ActorContext("tenant-a",SubjectKind.ACCOUNT,"another","trusted"),claim));
        }
    }

    @Test
    void acceptedReviewSurvivesCashPortFailureAndRecoversWithoutActorReapproval() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
            OfflineCollectionPort money=new OfflineCollectionPort() {
                public ManualResult confirm(ManualPayment command) {
                    if(fail.get())throw new IllegalStateException("test cut after intent commit");
                    assertEquals("operator-a",command.operatorNo());
                    return new ManualResult("UNMATCHED","actual-ledger-row");
                }
            };
            var api=service(db,f,money);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var evidence=api.reserveEvidence(f.mall().c.ops,q,"PLATFORM_RECEIPT");
            var command=new OfflinePurchaseFacade.ReviewCommand(q,"review",null,new Money("CNY",8700),Instant.ofEpochMilli(3000),
                    "platform","real-ref",evidence.assetKey(),false,"bank checked");
            assertThrows(IllegalStateException.class,()->api.confirm(f.mall().c.ops,command));
            assertEquals("ACCEPTED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action",String.class));
            fail.set(false);
            api.recover(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            assertEquals("UNMATCHED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action",String.class));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_fulfillment_task WHERE task_type='DIGITAL'",Integer.class));
            assertEquals(f.order().orderNo(),db.jdbc.queryForObject("SELECT pending_change_order_no FROM mch_subscription_account",String.class));
        }
    }

    @Test
    void definiteLedgerRefusalIsTerminalAndDoesNotBlockLaterRecovery() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            var cut=new java.util.concurrent.atomic.AtomicBoolean(true);
            OfflineCollectionPort money=new OfflineCollectionPort() {
                public ManualResult confirm(ManualPayment command) {
                    if("refused".equals(command.bankReference()))throw new IllegalArgumentException("OFFLINE_RECEIVER_MISMATCH");
                    if("stuck".equals(command.bankReference()))throw new IllegalStateException("provider still unknown");
                    if(cut.get())throw new IllegalStateException("test cut after intent commit");
                    return new ManualResult("UNMATCHED","actual-ledger-row");
                }
            };
            var api=service(db,f,money);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var evidence=api.reserveEvidence(f.mall().c.ops,q,"PLATFORM_RECEIPT");
            var later=new OfflinePurchaseFacade.ReviewCommand(q,"b-later",null,new Money("CNY",8700),Instant.ofEpochMilli(3000),
                    "platform","real-ref",evidence.assetKey(),false,"bank checked");
            assertThrows(IllegalStateException.class,()->api.confirm(f.mall().c.ops,later));
            var refused=new OfflinePurchaseFacade.ReviewCommand(q,"a-first",null,new Money("CNY",8700),Instant.ofEpochMilli(3000),
                    "wrong","refused",evidence.assetKey(),false,"bank checked");
            var e=assertThrows(MallException.class,()->api.confirm(f.mall().c.ops,refused));
            assertEquals("OFFLINE_RECEIVER_MISMATCH",e.code());
            assertEquals("REJECTED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action WHERE action_no='a-first'",String.class));
            var stuck=new OfflinePurchaseFacade.ReviewCommand(q,"a-stuck",null,new Money("CNY",8700),Instant.ofEpochMilli(3000),
                    "platform","stuck",evidence.assetKey(),false,"bank checked");
            assertThrows(IllegalStateException.class,()->api.confirm(f.mall().c.ops,stuck));
            cut.set(false);
            assertThrows(IllegalStateException.class,()->api.recover(new FulfillmentQuery("tenant-a",f.order().orderNo())));
            assertEquals("ACCEPTED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action WHERE action_no='a-stuck'",String.class));
            assertEquals("UNMATCHED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action WHERE action_no='b-later'",String.class));
            assertEquals("REJECTED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action WHERE action_no='a-first'",String.class));
        }
    }

    DefaultOfflinePurchaseFacade service(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f) { return service(db,f,new OfflineCollectionPort(){}); }
    DefaultOfflinePurchaseFacade service(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f,OfflineCollectionPort money) {
        var events=new JdbcCommerceEventPersistence(db.ds);
        return new DefaultOfflinePurchaseFacade(new JdbcMallRepository(db.ds,events),new JdbcFulfillmentRepository(db.ds),
                new JdbcOfflineRepository(db.ds),new JdbcSubscriptionAccountRepository(db.ds),f.mall().c.uow,
                s->Optional.of(new StoreSnapshot(s,"merchant","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1)),
                (a,s,cap)->new AuthorizationDecision(true,"TEST",Optional.empty()),null,f.svc(),null,
                money,
                new PrivateEvidencePort() {
                    public boolean stored(Scope scope,String key) { return true; }
                    public String signedRead(Scope scope,String key) { return "/private/test"; }
                },Clock.fixed(Instant.ofEpochMilli(5000),ZoneOffset.UTC),f.mall().c.ids);
    }
}
