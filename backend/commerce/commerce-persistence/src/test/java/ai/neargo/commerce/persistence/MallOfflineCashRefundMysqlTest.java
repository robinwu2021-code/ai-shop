package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.offline.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real commerce/MySQL transactions; monetary authority is separately integration-tested by host. */
class MallOfflineCashRefundMysqlTest {
    @Test void actualRemainderRefundThenReconciliationReleasesOnlyOriginalStoreHold(){
        try(var db=new MysqlDatabase()){
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            long[] returned={0};int[] payouts={0};PaymentRefundCommand[] intent={null};
            var target=new OfflineCashRefundPort.Target(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo(),false);
            var money=new OfflineCollectionPort(){
                public ManualResult confirm(ManualPayment p){return new ManualResult("UNMATCHED","STRAY");}
                public List<CashReceipt> receipts(String t,String o){return List.of(new CashReceipt("STRAY","U-STRAY","platform",new Money("CNY",8700),returned[0],false,true,false));}
                public void payout(RefundPayout p){assertEquals("REFUND_PROOF",db.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(scope_snapshot,'$.scope.purpose')) FROM mall_offline_evidence WHERE asset_key=?",String.class,p.evidenceReference()));assertEquals(5000,p.confirmedAt().toEpochMilli());returned[0]=p.actualAmount().minor();payouts[0]++;}
                public void reconcile(Reconciliation r){if(returned[0]!=8700)throw new IllegalArgumentException("OFFLINE_CASH_NOT_REFUNDED");f.bridge().closed=true;f.bridge().payment=new PaymentOutcome(f.order().orderNo(),Optional.of("pay"),"platform",f.order().payable(),AsyncStatus.PENDING,Optional.empty(),Optional.empty(),Map.of(),true,Optional.of("verified-bank-close"));}
            };
            var platform=new PlatformPaymentPort(){
                public PaymentOutcome create(PaymentIntentCommand c){return f.bridge().create(c);}public PaymentOutcome query(PaymentQuery q){return f.bridge().query(q);}public PaymentOutcome close(PaymentQuery q){return f.bridge().close(q);}
                public PaymentRefundOutcome refund(PaymentRefundCommand c){intent[0]=c;return queryRefund(new PaymentRefundQuery(c.tenantNo(),c.orderNo(),c.refundNo()));}
                public PaymentRefundOutcome queryRefund(PaymentRefundQuery q){var c=intent[0];var fact=returned[0]==8700?new VerifiedRefundFact(c.tenantNo(),"event",c.orderNo(),c.refundNo(),c.store(),c.paymentAttemptNo(),c.originalPaymentNo(),c.platformMerchantNo(),c.originalPaid(),c.amount(),"OUT",AsyncStatus.SUCCEEDED,true,Instant.ofEpochMilli(4000),"ledger:refund"):null;return new PaymentRefundOutcome(q.refundNo(),fact==null?AsyncStatus.PENDING:AsyncStatus.SUCCEEDED,Optional.of("OUT"),Optional.empty(),fact);}
            };
            var cash=new DefaultOfflineCashRefundPort(new JdbcCashRefundRepository(db.ds),f.mall().c.uow,money,platform,Clock.fixed(Instant.ofEpochMilli(5000),ZoneOffset.UTC));
            var api=new MallOfflineWorkflowMysqlTest().service(db,f,money).cashRefunds(cash);
            var q=new OrderQuery(target.store(),target.orderNo());var ops=f.mall().c.ops;
            api.issue(MallOrderConcurrencyMysqlTest.BUYER,q);
            var incoming=api.reserveEvidence(ops,q,"PLATFORM_RECEIPT");
            assertEquals("UNMATCHED",api.confirm(ops,new OfflinePurchaseFacade.ReviewCommand(q,"IN",null,new Money("CNY",8700),Instant.ofEpochMilli(3000),"platform","IN-BANK",incoming.assetKey(),false,"actual amount mismatch")).status());
            var check=api.reserveEvidence(ops,q,"RECONCILIATION");
            assertThrows(MallException.class,()->api.voidOrder(ops,new OfflinePurchaseFacade.VoidCommand(q,"UNSAFE","CHECK","no fake disposition",check.assetKey(),Map.of("STRAY","manually done"))));
            assertEquals(q.orderNo(),db.jdbc.queryForObject("SELECT pending_change_order_no FROM mch_subscription_account",String.class));
            var request=api.requestCashRefund(ops,q,new OfflineCashRefundPort.Request("cash-R1","STRAY","return actual funds"));
            assertEquals(8700,request.amount().minor());assertEquals("AWAITING_PAYOUT",request.status());
            var out=api.reserveEvidence(ops,q,"REFUND_PROOF");var proof=new OfflineCashRefundPort.Payout("OUT-A1",request.amount(),Instant.ofEpochMilli(4000),"verified original payer","OUT",out.assetKey());
            assertEquals("REFUNDED",api.payoutCashRefund(ops,q,request.refundNo(),proof).status());assertEquals("REFUNDED",api.payoutCashRefund(ops,q,request.refundNo(),proof).status());assertEquals(1,payouts[0]);
            var close=new OfflinePurchaseFacade.VoidCommand(q,"CLOSE","BANK-CHECK","full verified refund and reconciliation",check.assetKey(),Map.of("STRAY","VERIFIED_REFUNDED"));
            assertEquals("APPLIED",api.voidOrder(ops,close).status());
            assertNull(db.jdbc.queryForObject("SELECT pending_change_order_no FROM mch_subscription_account",String.class));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_fulfillment_task WHERE task_type='DIGITAL'",Integer.class));
            var later=f.mall().place(f.mall().quote(),"after-return");api.voidOrder(ops,close);
            assertEquals(later.orderNo(),db.jdbc.queryForObject("SELECT pending_change_order_no FROM mch_subscription_account",String.class));
            String buyerView=new tools.jackson.databind.ObjectMapper().writeValueAsString(api.cashRefunds(MallOrderConcurrencyMysqlTest.BUYER,q));assertFalse(buyerView.contains("verified original payer"));assertFalse(buyerView.contains(out.assetKey()));
        }
    }
}
