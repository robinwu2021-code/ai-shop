package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

class SalesCollectionWorkflowMysqlTest {
    @Test
    void operatorConfirmsSalesReceiptAndMerchantReadsSameOrderWithoutDuplicateRights() {
        try(var db=new MysqlDatabase()) {
            var f=new MallFulfillmentMysqlTest().setup(db,false);
            var terms=new OfflineCollectionTerms("p-sales",1,"platform","CNY","sales-key","w1",86400000,
                    Map.of("collectionMethod","SALES_COLLECTION","contactInstructions","test-only contact"));
            db.jdbc.update("UPDATE mall_quote SET line_snapshot=JSON_SET(line_snapshot,'$.purchase.offline',CAST(? AS JSON),'$.purchase.buyerKind','STORE')",
                    JsonMapper.builder().build().writeValueAsString(terms));
            var q=new OrderQuery(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo());
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",q.orderNo()));
            assertEquals("SALES_COLLECTION",f.bridge().intent.payMethod());
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var money=new OfflineCollectionPort() {
                public List<Collector> collectors(StoreRef store) { return List.of(new Collector("sales","Sales")); }
                public ManualResult confirm(ManualPayment c) {
                    calls.incrementAndGet();
                    assertNull(c.bankReference());assertEquals("receipt",c.collectionReference());
                    f.bridge().payment=new PaymentOutcome(q.orderNo(),Optional.of("pay"),"platform",f.order().payable(),
                            AsyncStatus.SUCCEEDED,Optional.of(Instant.ofEpochMilli(3000)),Optional.empty());
                    return new ManualResult("MATCHED","pay");
                }
            };
            var api=new MallOfflineWorkflowMysqlTest().service(db,f,money);
            var evidence=api.reserveEvidence(f.mall().c.ops,q,"PLATFORM_RECEIPT");
            var command=new OfflinePurchaseFacade.ReviewCommand(q,"sales-review",null,f.order().payable(),Instant.ofEpochMilli(3000),
                    "platform",null,evidence.assetKey(),false,"test receipt", "sales","receipt");
            assertEquals("SALES_COLLECTION",api.instructions(f.mall().c.ops,q).collectionMethod());
            assertFalse(api.instructions(f.mall().c.ops,q).instructions().containsKey("transferRemark"));
            assertEquals("PAYING",MallOfflineWorkflowMysqlTest.queries(db,f).order(MallOrderConcurrencyMysqlTest.BUYER,q).summary().paymentStatus());
            assertEquals("APPLIED",api.confirm(f.mall().c.ops,command).status());
            var periods=db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class);
            assertTrue(periods>0);
            assertEquals("APPLIED",api.confirm(f.mall().c.ops,command).status());
            assertEquals(1,calls.get());
            assertEquals(periods,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class));
            var merchant=MallOfflineWorkflowMysqlTest.queries(db,f).order(MallOrderConcurrencyMysqlTest.BUYER,q);
            assertEquals("PAID",merchant.summary().paymentStatus());
            assertEquals("SALES_COLLECTION",merchant.quote().collectionMethod());
            assertEquals(q.orderNo(),merchant.summary().order().orderNo());
        }
    }
}
