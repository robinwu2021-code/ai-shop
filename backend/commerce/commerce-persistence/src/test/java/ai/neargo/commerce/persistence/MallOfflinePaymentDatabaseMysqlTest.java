package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.OrderQuery;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade.*;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.commerce.core.offline.OfflineRepository.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MallOfflinePaymentDatabaseMysqlTest {
    @Test void preservesScopedClaimsActionsEvidenceAndOrderedOnlyReports() {
        try (var db = new MysqlDatabase()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/mall/offline/V1__offline_payment.sql")).execute(db.ds);
            db.jdbc.execute("""
                    CREATE TABLE mall_order (tenant_no VARCHAR(32),order_no VARCHAR(64),entity_no VARCHAR(64),
                    store_no VARCHAR(64),currency VARCHAR(8),cash_due_minor BIGINT,pay_status VARCHAR(16),
                    ordered_at DATETIME(3),stage VARCHAR(16),pricing_policy_version VARCHAR(64))
                    """);
            db.jdbc.update("""
                    INSERT INTO mall_order VALUES ('T','O','E','S','USD',10,'UNPAID',NOW(),'ORDERED','mall-allocation-v1'),
                    ('T','Q','E','S','USD',10,'UNPAID',NOW(),'QUOTE','mall-allocation-v1'),('B','O','E','S','USD',99,'PAID',NOW(),'ORDERED','mall-allocation-v1')
                    """);
            var repo = new JdbcMallOfflineRepository(db.ds);
            var report = new JdbcPurchaseOrderQuery(db.ds,true);
            var tx = new JdbcCommerceUnitOfWork(db.ds,new DataSourceTransactionManager(db.ds));
            var order = new OrderQuery(new StoreRef("T","S"),"O");
            var scope = new PrivateEvidencePort.Scope(order.store(),"E","O",SubjectKind.ACCOUNT,"buyer","RECEIPT");
            var evidence = new Evidence("proof",scope,1000);
            var command = new ClaimCommand(order,"same",new Money("USD",10),Instant.ofEpochMilli(1000),"bank",List.of("proof"));
            var claim = new Claim(command,"buyer",2000,"SUBMITTED",null,null);
            var action = new Action("same","VOID",order,"ops",3000,null,null,null,"ACCEPTED",null);
            tx.required(() -> {
                repo.reserve(evidence);
                repo.saveClaim(claim);
                repo.saveAction(action,"approval-cash");
                assertEquals(evidence,repo.evidence("T","proof").orElseThrow());
                assertEquals(List.of("proof"),repo.retained("T"));
                assertEquals(claim,repo.claim("T","O","same").orElseThrow());
                assertEquals(action,repo.action("T","O","same").orElseThrow());
                assertEquals(List.of(claim),repo.submittedClaims("T",null,null,10));
                assertTrue(repo.submittedClaims("T","O","same",10).isEmpty());
                assertEquals(List.of("O"),repo.pendingOrders("T",null,10));
                assertEquals(List.of(action),repo.pending("T","O"));
                assertTrue(repo.claims("B","O").isEmpty());
                var rows=report.search(new Query(new Access("T",false,Set.of("E")),new Filter(null,null,null,null,null,null,null,true),null,10));
                assertEquals(1,rows.size());
                assertEquals("SUBMITTED",rows.getFirst().claimStatus());
                assertEquals(List.of("O"),report.findByOrderNos("T",List.of("Q","O")).stream().map(Row::orderNo).toList());
                repo.saveAction(action.completed("APPLIED","payment"));
                repo.saveClaim(claim.reviewed("DECLINED","ops","no transfer"));
                assertTrue(repo.pending("T","O").isEmpty());
                assertTrue(repo.submittedClaims("T",null,null,10).isEmpty());
                assertEquals("approval-cash", db.jdbc.queryForObject(
                        "SELECT review_no FROM mall_offline_payment WHERE record_type='ACTION'",String.class));
                assertEquals("APPLIED",repo.actions("T","O").getFirst().status());
                assertEquals("DECLINED",repo.claims("T","O").getFirst().status());
                return null;
            });
            assertEquals("same",db.jdbc.queryForObject("SELECT action_no FROM mall_offline_evidence",String.class));
        }
    }
}
