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
            var declined=api.claims(MallOrderConcurrencyMysqlTest.BUYER,q).getFirst();
            assertEquals("illegible",declined.reviewNote());
            assertNull(declined.receivedAmount());
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
            var sweep=api.recoverPending("tenant-a",null,10);
            assertEquals(1,sweep.visited());
            assertEquals(1,sweep.failed());
            assertNull(sweep.nextOrderNo());
            assertEquals(0,api.recoverPending("tenant-a",f.order().orderNo(),10).visited());
            assertEquals("UNMATCHED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action WHERE action_no='b-later'",String.class));
            assertEquals("REJECTED",db.jdbc.queryForObject("SELECT status FROM mall_offline_action WHERE action_no='a-first'",String.class));
        }
    }

    @Test
    void suspendedStoreStillLetsOperatorRecordMoneyButNotMerchantDeclare() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            OfflineCollectionPort money=new OfflineCollectionPort() {
                public ManualResult confirm(ManualPayment command) { return new ManualResult("UNMATCHED","ledger-row"); }
            };
            var api=service(db,f,money,StoreStatus.DISABLED);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var e=assertThrows(MallException.class,()->api.reserveEvidence(MallOrderConcurrencyMysqlTest.BUYER,q,"BUYER_RECEIPT"));
            assertEquals("STORE_UNAVAILABLE",e.code());
            var evidence=api.reserveEvidence(f.mall().c.ops,q,"PLATFORM_RECEIPT");
            var view=api.confirm(f.mall().c.ops,new OfflinePurchaseFacade.ReviewCommand(q,"suspended",null,new Money("CNY",8700),
                    Instant.ofEpochMilli(3000),"platform","ref",evidence.assetKey(),false,"bank checked"));
            assertEquals("UNMATCHED",view.status());
        }
    }

    @Test
    void missingCollectionsAreInvalidRequestsAndClaimFollowsReviewOutcome() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            var refuse=new java.util.concurrent.atomic.AtomicBoolean(true);
            OfflineCollectionPort money=new OfflineCollectionPort() {
                public ManualResult confirm(ManualPayment command) {
                    if(refuse.get())throw new IllegalArgumentException("OFFLINE_RECEIVER_MISMATCH");
                    return new ManualResult("UNMATCHED","ledger-row");
                }
            };
            var api=service(db,f,money);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var ops=f.mall().c.ops;
            assertEquals("INVALID_REQUEST",assertThrows(MallException.class,()->api.submit(MallOrderConcurrencyMysqlTest.BUYER,
                    new OfflinePurchaseFacade.ClaimCommand(q,"c",new Money("CNY",8800),Instant.ofEpochMilli(3000),"r",null))).code());
            assertEquals("INVALID_REQUEST",assertThrows(MallException.class,()->api.confirm(ops,
                    new OfflinePurchaseFacade.ReviewCommand(q,"x",null,new Money("CNY",8800),Instant.ofEpochMilli(3000),"platform","r",null,false,"why"))).code());
            assertEquals("INVALID_REQUEST",assertThrows(MallException.class,()->api.voidOrder(ops,
                    new OfflinePurchaseFacade.VoidCommand(q,"v","recon","why","evidence",null))).code());
            var ticket=api.reserveEvidence(MallOrderConcurrencyMysqlTest.BUYER,q,"BUYER_RECEIPT");
            api.submit(MallOrderConcurrencyMysqlTest.BUYER,new OfflinePurchaseFacade.ClaimCommand(q,"claim",new Money("CNY",8800),
                    Instant.ofEpochMilli(3000),"buyer-ref",List.of(ticket.assetKey())));
            // Operators match the transfer by the buyer's own reference and receipts (read back from the stored snapshot)
            var seen=api.claims(ops,q).getFirst();
            assertEquals("buyer-ref",seen.bankReference());
            assertEquals(List.of(ticket.assetKey()),seen.evidenceKeys());
            var evidence=api.reserveEvidence(ops,q,"PLATFORM_RECEIPT");
            var refused=new OfflinePurchaseFacade.ReviewCommand(q,"r1","claim",new Money("CNY",8700),Instant.ofEpochMilli(3000),
                    "platform","bank-ref",evidence.assetKey(),false,"bank checked");
            assertThrows(MallException.class,()->api.confirm(ops,refused));
            assertEquals("SUBMITTED",status(db,"claim"));
            refuse.set(false);
            var applied=new OfflinePurchaseFacade.ReviewCommand(q,"r2","claim",new Money("CNY",8700),Instant.ofEpochMilli(3000),
                    "platform","bank-ref",evidence.assetKey(),false,"bank checked");
            assertEquals("UNMATCHED",api.confirm(ops,applied).status());
            assertEquals("MISMATCHED",status(db,"claim"));
            // P3-3: buyer sees what the platform actually recorded and why, never operator evidence.
            var mismatched=api.claims(MallOrderConcurrencyMysqlTest.BUYER,q).getFirst();
            assertEquals(new Money("CNY",8700),mismatched.receivedAmount());
            assertEquals("bank checked",mismatched.reviewNote());
            var wire=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(mismatched);
            assertFalse(wire.contains(evidence.assetKey()),wire);
            assertFalse(wire.contains("bank-ref"),wire);
            assertFalse(wire.contains("platform"),wire);
            // P3-4: order summary carries the latest claim status.
            var queries=queries(db,f);
            assertEquals("MISMATCHED",queries.order(MallOrderConcurrencyMysqlTest.BUYER,q).summary().latestClaimStatus());
            assertEquals("MISMATCHED",queries.orders(MallOrderConcurrencyMysqlTest.BUYER,q.store(),null,20).getFirst().latestClaimStatus());
            assertEquals("CLAIM_ALREADY_REVIEWED",assertThrows(MallException.class,()->api.decline(ops,
                    new OfflinePurchaseFacade.DeclineCommand(q,"claim","late decline"))).code());
            assertEquals("CLAIM_ALREADY_REVIEWED",assertThrows(MallException.class,()->api.confirm(ops,
                    new OfflinePurchaseFacade.ReviewCommand(q,"r3","claim",new Money("CNY",8800),Instant.ofEpochMilli(3000),
                            "platform","other-ref",evidence.assetKey(),false,"again"))).code());
        }
    }

    @Test
    void evidenceUploadIsOwnReservationOnlyAndImmutableOnceStored() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            var bytes=new java.util.HashMap<String,PrivateEvidencePort.Scope>();
            var media=new PrivateEvidencePort() {
                public boolean stored(Scope scope,String key) { return scope.equals(bytes.get(key)); }
                public String signedRead(Scope scope,String key) { return "/private/"+key; }
                public void store(Scope scope,String key,Upload upload) { bytes.put(key,scope); }
            };
            var api=service(db,f,new OfflineCollectionPort(){},StoreStatus.ACTIVE,media);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var buyer=MallOrderConcurrencyMysqlTest.BUYER;var ops=f.mall().c.ops;
            var image=new PrivateEvidencePort.Upload(()->new java.io.ByteArrayInputStream(new byte[]{1}),1,"image/jpeg");
            var mine=api.reserveEvidence(buyer,q,"BUYER_RECEIPT");
            var platform=api.reserveEvidence(ops,q,"PLATFORM_RECEIPT");
            assertTrue(mine.assetKey().matches("[A-Za-z0-9_\\-/]+\\.jpg"));
            assertEquals("INVALID_REQUEST",assertThrows(MallException.class,()->api.upload(buyer,q,mine.assetKey(),
                    new PrivateEvidencePort.Upload(image.content(),1,"application/pdf"))).code());
            assertEquals("EVIDENCE_FORBIDDEN",assertThrows(MallException.class,()->api.upload(buyer,q,platform.assetKey(),image)).code());
            assertEquals("EVIDENCE_FORBIDDEN",assertThrows(MallException.class,()->api.upload(ops,q,mine.assetKey(),image)).code());
            api.upload(buyer,q,mine.assetKey(),image);
            assertEquals("EVIDENCE_ALREADY_STORED",assertThrows(MallException.class,()->api.upload(buyer,q,mine.assetKey(),image)).code());
            assertEquals(List.of(mine.assetKey()),List.copyOf(bytes.keySet()));
        }
    }

    @Test
    void refundPayoutNeedsOperatorRefundProofThenRecoversTheRefund() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            var payouts=new java.util.ArrayList<OfflineCollectionPort.RefundPayout>();
            OfflineCollectionPort money=new OfflineCollectionPort() {
                public void payout(RefundPayout p) {
                    if("bad".equals(p.bankReference()))throw new IllegalArgumentException("OFFLINE_PAYOUT_AMOUNT_MISMATCH");
                    payouts.add(p);
                }
            };
            var recovered=new java.util.ArrayList<RefundQuery>();
            var refunds=(RefundFacade)java.lang.reflect.Proxy.newProxyInstance(RefundFacade.class.getClassLoader(),new Class<?>[]{RefundFacade.class},
                    (proxy,m,args)->{ if(m.getName().equals("recover"))recovered.add((RefundQuery)args[0]); return null; });
            var events=new JdbcCommerceEventPersistence(db.ds);
            var api=new DefaultOfflinePurchaseFacade(new JdbcMallRepository(db.ds,events),new JdbcFulfillmentRepository(db.ds),
                    new JdbcOfflineRepository(db.ds),new JdbcSubscriptionAccountRepository(db.ds),f.mall().c.uow,
                    s->Optional.of(new StoreSnapshot(s,"merchant","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1)),
                    (a,s,cap)->new AuthorizationDecision(true,"TEST",Optional.empty()),null,f.svc(),refunds,money,
                    new PrivateEvidencePort() {
                        public boolean stored(Scope scope,String key) { return true; }
                        public String signedRead(Scope scope,String key) { return "/private/test"; }
                    },Clock.fixed(Instant.ofEpochMilli(5000),ZoneOffset.UTC),f.mall().c.ids);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var ops=f.mall().c.ops;
            var proof=api.reserveEvidence(ops,q,"REFUND_PROOF");
            var receipt=api.reserveEvidence(ops,q,"PLATFORM_RECEIPT");
            assertEquals("FORBIDDEN",assertThrows(MallException.class,()->api.confirmRefundPayout(MallOrderConcurrencyMysqlTest.BUYER,
                    new OfflinePurchaseFacade.RefundPayoutCommand(q,"refund-1",new Money("CNY",100),Instant.ofEpochMilli(3000),"payee","out",proof.assetKey()))).code());
            assertEquals("EVIDENCE_FORBIDDEN",assertThrows(MallException.class,()->api.confirmRefundPayout(ops,
                    new OfflinePurchaseFacade.RefundPayoutCommand(q,"refund-1",new Money("CNY",100),Instant.ofEpochMilli(3000),"payee","out",receipt.assetKey()))).code());
            assertEquals("OFFLINE_PAYOUT_AMOUNT_MISMATCH",assertThrows(MallException.class,()->api.confirmRefundPayout(ops,
                    new OfflinePurchaseFacade.RefundPayoutCommand(q,"refund-1",new Money("CNY",100),Instant.ofEpochMilli(3000),"payee","bad",proof.assetKey()))).code());
            assertTrue(recovered.isEmpty());
            api.confirmRefundPayout(ops,new OfflinePurchaseFacade.RefundPayoutCommand(q,"refund-1",new Money("CNY",100),Instant.ofEpochMilli(3000),"payee","out",proof.assetKey()));
            assertEquals(1,payouts.size());
            assertEquals(ops.principalNo(),payouts.getFirst().operatorNo());
            assertEquals(List.of(new RefundQuery("tenant-a",f.order().orderNo(),"refund-1")),recovered);
        }
    }

    /** P3-3: the buyer is told what to write on the transfer; it is the order number ops searches by. */
    @Test
    void instructionsCarryTransferRemarkAndConfirmedClaimShowsReceivedAmount() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);
            new MallOfflineDeadlineMysqlTest().freezeOffline(db,86400000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            OfflineCollectionPort money=new OfflineCollectionPort() {
                public ManualResult confirm(ManualPayment command) { return new ManualResult("MATCHED","ledger-row"); }
            };
            var api=service(db,f,money);
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            var instructions=api.instructions(MallOrderConcurrencyMysqlTest.BUYER,q).instructions();
            assertEquals(f.order().orderNo().replaceAll("[^A-Za-z0-9]",""),instructions.get("transferRemark"));
            assertEquals("test-only",instructions.get("bank"));
            var queries=queries(db,f);
            assertNull(queries.order(MallOrderConcurrencyMysqlTest.BUYER,q).summary().latestClaimStatus());
            var ticket=api.reserveEvidence(MallOrderConcurrencyMysqlTest.BUYER,q,"BUYER_RECEIPT");
            api.submit(MallOrderConcurrencyMysqlTest.BUYER,new OfflinePurchaseFacade.ClaimCommand(q,"claim",new Money("CNY",8800),
                    Instant.ofEpochMilli(3000),"buyer-ref",List.of(ticket.assetKey())));
            var submitted=api.claims(MallOrderConcurrencyMysqlTest.BUYER,q).getFirst();
            assertNull(submitted.receivedAmount());
            assertNull(submitted.reviewNote());
            assertEquals("SUBMITTED",queries.order(MallOrderConcurrencyMysqlTest.BUYER,q).summary().latestClaimStatus());
            var evidence=api.reserveEvidence(f.mall().c.ops,q,"PLATFORM_RECEIPT");
            api.confirm(f.mall().c.ops,new OfflinePurchaseFacade.ReviewCommand(q,"r1","claim",new Money("CNY",8800),Instant.ofEpochMilli(3000),
                    "platform","bank-ref",evidence.assetKey(),false,"internal note"));
            var confirmed=api.claims(MallOrderConcurrencyMysqlTest.BUYER,q).getFirst();
            assertEquals("CONFIRMED",confirmed.status());
            assertEquals(new Money("CNY",8800),confirmed.receivedAmount());
            assertNull(confirmed.reviewNote());
            assertEquals("CONFIRMED",queries.order(MallOrderConcurrencyMysqlTest.BUYER,q).summary().latestClaimStatus());
        }
    }

    static ai.neargo.commerce.core.query.DefaultMallQueryFacade queries(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f) {
        var events=new JdbcCommerceEventPersistence(db.ds);
        return new ai.neargo.commerce.core.query.DefaultMallQueryFacade(new JdbcMallRepository(db.ds,events),new JdbcFulfillmentRepository(db.ds),
                new JdbcRefundRepository(db.ds,f.mall().c.ids),new JdbcSubscriptionAccountRepository(db.ds),f.mall().c.uow,
                s->Optional.of(new StoreSnapshot(s,"merchant","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1)),
                (a,s,cap)->new AuthorizationDecision(true,"TEST",Optional.empty()),new JdbcOfflineRepository(db.ds));
    }

    static String status(MysqlDatabase db,String claim) {
        return db.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(claim_snapshot,'$.status')) FROM mall_offline_claim WHERE claim_no=?",String.class,claim);
    }

    DefaultOfflinePurchaseFacade service(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f) { return service(db,f,new OfflineCollectionPort(){}); }
    DefaultOfflinePurchaseFacade service(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f,OfflineCollectionPort money) {
        return service(db,f,money,StoreStatus.ACTIVE);
    }
    DefaultOfflinePurchaseFacade service(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f,OfflineCollectionPort money,StoreStatus storeStatus) {
        return service(db,f,money,storeStatus,new PrivateEvidencePort() {
            public boolean stored(Scope scope,String key) { return true; }
            public String signedRead(Scope scope,String key) { return "/private/test"; }
        });
    }
    DefaultOfflinePurchaseFacade service(MysqlDatabase db,MallFulfillmentMysqlTest.Fixture f,OfflineCollectionPort money,StoreStatus storeStatus,PrivateEvidencePort media) {
        var events=new JdbcCommerceEventPersistence(db.ds);
        return new DefaultOfflinePurchaseFacade(new JdbcMallRepository(db.ds,events),new JdbcFulfillmentRepository(db.ds),
                new JdbcOfflineRepository(db.ds),new JdbcSubscriptionAccountRepository(db.ds),f.mall().c.uow,
                s->Optional.of(new StoreSnapshot(s,"merchant","RETAIL",storeStatus,CommerceModel.STORE_V1)),
                (a,s,cap)->new AuthorizationDecision(true,"TEST",Optional.empty()),null,f.svc(),null,
                money,media,Clock.fixed(Instant.ofEpochMilli(5000),ZoneOffset.UTC),f.mall().c.ids);
    }
}
