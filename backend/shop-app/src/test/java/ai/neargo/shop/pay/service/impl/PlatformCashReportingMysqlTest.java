package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.service.PlatformCashReporting;
import ai.neargo.shop.pay.service.PlatformCollectionService;
import ai.neargo.shop.pay.service.PlatformRefundService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual canonical writers, proof JSON and reporting SQL against an isolated native MySQL schema. */
@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*")
class PlatformCashReportingMysqlTest {
    @Test void actualLedgerReceiptsAndRefundsRemainDisjointSanitizedAndReadOnly() throws Exception {
        try(var f=new PlatformOfflineCollectionMysqlTest.Fixture()) {
            assertTrue(f.base.jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("9."));
            var original=f.request("NORMAL",f.profile(1,"TWD"));
            f.base.service.create(original);
            var matchedProof=f.proof(original,"matched",10000,"TWD","NORMAL",5000);
            var matched=f.base.service.confirmOffline(matchedProof);
            assertEquals("MATCHED",matched.disposition());
            assertEquals(matched,f.base.service.confirmOffline(matchedProof));
            // A second real deposit, in a different currency, belongs to a separate unmatched cash row.
            var extraProof=f.proof(original,"second-usd",777,"USD","NORMAL",6000);
            var unmatched=f.base.service.confirmOffline(extraProof);
            assertEquals("UNMATCHED",unmatched.disposition());
            assertEquals(unmatched,f.base.service.confirmOffline(extraProof));
            var refundService=f.refunds();
            var refundKey=new PlatformRefundService.Key("MAIN","NORMAL","cash-return");
            var stray=unmatched.receipt();
            refundService.refund(new PlatformRefundService.Request(refundKey,"S1",stray.key().attemptNo(),stray.paymentNo(),"platform-mch",777,777,"USD","return actual cash"));
            var payout=new PlatformRefundService.OfflinePayout(refundKey,"ops",777,"USD",12000,"private-payee","payout-usd","private-proof",13000);
            var completed=refundService.confirmOfflinePayout(payout);
            assertEquals("SUCCESS",completed.state());
            assertEquals(completed,refundService.confirmOfflinePayout(payout));
            var closed=f.request("CLOSED",original.offline());
            f.base.service.create(closed);
            f.base.service.reconcileOffline(new PlatformCollectionService.OfflineVoid(closed.key(),"ops","reconciled","no cash seen","private-proof",20000,Map.of()));
            assertEquals("LATE",f.base.service.confirmOffline(f.proof(closed,"discovered",10000,"TWD","NORMAL",7000)).disposition());

            var reporting=new PlatformCashReportingImpl(f.base.ds,new DataSourceTransactionManager(f.base.ds));
            long versionBefore=f.base.jdbc.queryForObject("SELECT SUM(version) FROM stl_payment",Long.class);
            var facts=new ArrayList<PlatformCashReporting.Fact>();
            reporting.scan(new PlatformCashReporting.Query("MAIN",5000,13000,null),batch->{
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                facts.addAll(batch);
            });
            assertEquals(4,facts.size());
            assertEquals(4,facts.stream().map(PlatformCashReporting.Fact::paymentNo).distinct().count());
            assertTrue(facts.stream().allMatch(PlatformCashReporting.Fact::verified));
            assertEquals(List.of(5000L,6000L,7000L,12000L),facts.stream().map(PlatformCashReporting.Fact::occurredAt).toList());
            assertEquals(List.of("NORMAL","UNMATCHED","CLOSED_ORDER","UNMATCHED"),facts.stream().map(PlatformCashReporting.Fact::classification).toList());
            assertEquals(List.of("PAY","PAY","PAY","REFUND"),facts.stream().map(PlatformCashReporting.Fact::direction).toList());
            assertEquals(stray.paymentNo(),facts.getLast().originalPaymentNo());
            assertEquals(777,facts.getLast().amountMinor());
            assertEquals(20000,facts.stream().filter(x->x.currency().equals("TWD")&&x.direction().equals("PAY")).mapToLong(PlatformCashReporting.Fact::amountMinor).sum());
            var usd=new ArrayList<PlatformCashReporting.Fact>();
            reporting.scan(new PlatformCashReporting.Query("MAIN",0,13000,"USD"),usd::addAll);
            assertEquals(2,usd.size());
            assertEquals(0,usd.stream().mapToLong(x->x.direction().equals("PAY")?x.amountMinor():-x.amountMinor()).sum());
            assertEquals(versionBefore,f.base.jdbc.queryForObject("SELECT SUM(version) FROM stl_payment",Long.class));
            verifyNoInteractions(f.base.gateway);

            f.base.jdbc.update("UPDATE stl_payment SET refund_verified_minor=1 WHERE payment_no=?",completed.paymentNo());
            var invalid=new ArrayList<PlatformCashReporting.Fact>();
            reporting.scan(new PlatformCashReporting.Query("MAIN",0,13000,null),invalid::addAll);
            var candidate=invalid.stream().filter(x->x.paymentNo().equals(completed.paymentNo())).findFirst().orElseThrow();
            assertFalse(candidate.verified());assertEquals("PLATFORM_CASH_REFUND_UNVERIFIED",candidate.diagnostic());
            assertFalse(candidate.toString().contains("private"));
        }
    }
    @Test void salesCollectionProofsAndRefundsUseTheSameAuthorityAsTheirCanonicalWriter() throws Exception {
        try(var f=new PlatformOfflineCollectionMysqlTest.Fixture()) {
            var terms=f.base.service.configureOffline(new PlatformCollectionService.OfflineProfile("MAIN","sales-report",0,"platform-mch","TWD",null,null,null,"w1",86400000,true,"ops","test only","SALES_COLLECTION","Contact assigned sales"));
            var request=new PlatformCollectionService.Request(new PlatformCollectionService.Key("MAIN","SALES","A"),"buyer","platform-mch","OFFLINE","SALES_COLLECTION",10000,"TWD",86401000,1000,null,terms);
            f.base.service.create(request);
            var paid=f.base.service.confirmOffline(new PlatformCollectionService.OfflineProof(request.key(),"ops","platform-mch",10000,"TWD",1000,null,"private-proof","NORMAL",2000,"collector","receipt-1"));
            var refundKey=new PlatformRefundService.Key("MAIN","SALES","refund");
            f.refunds().refund(new PlatformRefundService.Request(refundKey,"S1","A",paid.receipt().paymentNo(),"platform-mch",10000,4000,"TWD","test return"));
            f.refunds().confirmOfflinePayout(new PlatformRefundService.OfflinePayout(refundKey,"ops",4000,"TWD",5000,"test-recipient","test-refund-reference","test-proof",6000));
            var testRequest=new PlatformCollectionService.Request(new PlatformCollectionService.Key("MAIN","SALES-TEST","A"),"buyer","platform-mch","OFFLINE","SALES_COLLECTION",7000,"TWD",86401000,1000,null,terms);
            f.base.service.create(testRequest);
            var testPaid=f.base.service.confirmOffline(new PlatformCollectionService.OfflineProof(testRequest.key(),"ops","platform-mch",7000,"TWD",2000,null,"private-test-proof","TEST",3000,"collector","TEST-report-receipt"));
            var rawBefore=f.base.jdbc.queryForList("SELECT payment_no,status,version,platform_manual_proof FROM stl_payment ORDER BY payment_no");
            var reporting=new PlatformCashReportingImpl(f.base.ds,new DataSourceTransactionManager(f.base.ds));var facts=new ArrayList<PlatformCashReporting.Fact>();
            reporting.scan(new PlatformCashReporting.Query("MAIN",0,10000,"TWD"),facts::addAll);
            assertTrue(facts.stream().noneMatch(x->x.paymentNo().equals(testPaid.receipt().paymentNo())));
            assertEquals(rawBefore,f.base.jdbc.queryForList("SELECT payment_no,status,version,platform_manual_proof FROM stl_payment ORDER BY payment_no"));
            assertEquals(2,facts.size());assertTrue(facts.stream().allMatch(PlatformCashReporting.Fact::verified));
            assertEquals(6000,facts.stream().mapToLong(x->x.direction().equals("PAY")?x.amountMinor():-x.amountMinor()).sum());
            f.base.jdbc.update("UPDATE stl_payment SET platform_collection_ref_key='forged' WHERE payment_no=?",paid.receipt().paymentNo());facts.clear();
            reporting.scan(new PlatformCashReporting.Query("MAIN",0,10000,"TWD"),facts::addAll);
            assertTrue(facts.stream().filter(x->x.direction().equals("PAY")).noneMatch(PlatformCashReporting.Fact::verified));
        }
    }

    @Test void lockedWorkflowRetainsItsRefundCapacityAndPendingSemantics() throws Exception {
        try(var f=new PlatformOfflineCollectionMysqlTest.Fixture()) {
            var original=f.request("locked-reporting",f.profile(1,"TWD"));
            f.base.service.create(original);
            var paid=f.base.service.confirmOffline(f.proof(original,"locked-receipt",10000,"TWD","NORMAL",5000));
            var key=new PlatformRefundService.Key("MAIN","locked-reporting","partial-return");
            f.refunds().refund(new PlatformRefundService.Request(key,"S1",original.key().attemptNo(),paid.receipt().paymentNo(),"platform-mch",10000,2000,"TWD","return"));
            var refunded=f.refunds().confirmOfflinePayout(new PlatformRefundService.OfflinePayout(key,"ops",2000,"TWD",6000,"private-payee","locked-refund-reference","private-proof",7000));
            var sql=new ArrayList<String>();
            var observed=new org.springframework.jdbc.datasource.DelegatingDataSource(f.base.ds) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection=super.getConnection();
                    return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                        if(method.getName().equals("prepareStatement"))sql.add((String)args[0]);
                        try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
            var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(observed));
            var ledger=new PlatformOfflineLedger(new org.springframework.jdbc.core.JdbcTemplate(observed),transaction,tools.jackson.databind.json.JsonMapper.builder().build(),null);
            var receipts=transaction.execute(tx->ledger.receipts("MAIN",original.key().orderNo()));
            assertEquals(1,receipts.size());assertEquals(2000,receipts.getFirst().refundedMinor());assertFalse(receipts.getFirst().pendingRefund());
            assertTrue(sql.stream().anyMatch(statement->statement.contains("FOR UPDATE")));
            f.base.jdbc.update("UPDATE stl_payment SET refund_verified_minor=1 WHERE payment_no=?",refunded.paymentNo());
            var invalid=transaction.execute(tx->ledger.receipts("MAIN",original.key().orderNo()));
            assertEquals(1,invalid.size());assertEquals(0,invalid.getFirst().refundedMinor());assertTrue(invalid.getFirst().pendingRefund());
        }
    }
}
