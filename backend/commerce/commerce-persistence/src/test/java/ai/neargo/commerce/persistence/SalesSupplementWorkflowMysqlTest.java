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

class SalesSupplementWorkflowMysqlTest {
    @Test
    void salesTestConfirmationCanSupplementEvidenceWithoutChangingMoneyOrRights() {
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
                    assertNull(c.bankReference());assertEquals("TEST-receipt",c.collectionReference());assertTrue(c.testPayment());assertTrue(c.evidenceReference().startsWith("DOCUMENTATION_PENDING:"));
                    f.bridge().payment=new PaymentOutcome(q.orderNo(),Optional.of("pay"),"platform",f.order().payable(),
                            AsyncStatus.SUCCEEDED,Optional.of(Instant.ofEpochMilli(3000)),Optional.empty());
                    return new ManualResult("MATCHED","pay");
                }
            };
            var api=new MallOfflineWorkflowMysqlTest().service(db,f,money);

            var command=new OfflinePurchaseFacade.ReviewCommand(q,"sales-review",null,f.order().payable(),Instant.ofEpochMilli(3000),
                    "platform",null,null,false,"test receipt", "sales","TEST-receipt",true);
            assertEquals("FORBIDDEN",assertThrows(MallException.class,()->api.confirm(f.mall().c.ops,command)).code());
            api.testStores(Set.of(q.store().storeNo()));
            assertEquals("SALES_COLLECTION",api.instructions(f.mall().c.ops,q).collectionMethod());
            assertFalse(api.instructions(f.mall().c.ops,q).instructions().containsKey("transferRemark"));
            assertEquals("PAYING",MallOfflineWorkflowMysqlTest.queries(db,f).order(MallOrderConcurrencyMysqlTest.BUYER,q).summary().paymentStatus());
            assertEquals("APPLIED",api.confirm(f.mall().c.ops,command).status());
            var periods=db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class);
            assertTrue(periods>0);
            assertEquals("APPLIED",api.confirm(f.mall().c.ops,command).status());
            assertEquals(1,calls.get());
            assertEquals(periods,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class));
            var documentation=api.documentation(f.mall().c.ops,q);
            assertTrue(documentation.testAllowed());assertTrue(documentation.confirmations().getFirst().testPayment());
            assertFalse(documentation.confirmations().getFirst().complete());
            var note=new OfflinePurchaseFacade.SupplementCommand(q,"supplement-note","sales-review","Test metadata, no funds",List.of());
            assertEquals("APPLIED",api.supplement(f.mall().c.ops,note).status());
            assertFalse(api.documentation(f.mall().c.ops,q).confirmations().getFirst().complete());
            var evidence=api.reserveEvidence(f.mall().c.ops,q,"PLATFORM_RECEIPT");
            var extra=new OfflinePurchaseFacade.SupplementCommand(q,"supplement-proof","sales-review","Test attachment",List.of(evidence.assetKey()));
            assertEquals(api.supplement(f.mall().c.ops,extra),api.supplement(f.mall().c.ops,extra));
            var complete=api.documentation(f.mall().c.ops,q).confirmations().getFirst();
            assertTrue(complete.complete());assertEquals(2,complete.supplements().size());
            assertNull(complete.evidenceKey());assertEquals(command.actualAmount(),complete.actualAmount());
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(MallException.class,()->api.supplement(f.mall().c.ops,
                    new OfflinePurchaseFacade.SupplementCommand(q,"supplement-proof","sales-review","Changed",List.of()))).code());
            assertEquals("FORBIDDEN",assertThrows(MallException.class,()->api.documentation(MallOrderConcurrencyMysqlTest.BUYER,q)).code());
            assertEquals("EVIDENCE_FORBIDDEN",assertThrows(MallException.class,()->api.supplement(f.mall().c.ops,
                    new OfflinePurchaseFacade.SupplementCommand(q,"bad-proof","sales-review","Foreign",List.of("unknown.png")))).code());
            assertEquals(1,calls.get());assertEquals(periods,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class));
            var merchant=MallOfflineWorkflowMysqlTest.queries(db,f).order(MallOrderConcurrencyMysqlTest.BUYER,q);
            assertEquals("PAID",merchant.summary().paymentStatus());
            assertEquals("SALES_COLLECTION",merchant.quote().collectionMethod());
            assertEquals(q.orderNo(),merchant.summary().order().orderNo());
        }
    }
}
