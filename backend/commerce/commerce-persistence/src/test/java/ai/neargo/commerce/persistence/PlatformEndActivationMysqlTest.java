package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.Period;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PlatformEndActivationMysqlTest {
    @Test void purchaseRollbackPlatformFailureRetryAndRevocationHaveSeparateCommitBoundaries() {
        try (var mall = new MysqlDatabase(); var platform = new MysqlDatabase()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/mall/subscription/V1__mall_subscription.sql"),
                    new ClassPathResource("db/mall/fulfillment/V1__mall_fulfillment.sql")).execute(mall.ds);
            platformSchema(platform);
            mall.jdbc.execute("""
                    CREATE TABLE mall_end_entitlement(tenant_no VARCHAR(32),end_code VARCHAR(64),
                    entitlement_code VARCHAR(64),scope VARCHAR(16),client_id VARCHAR(128),enabled INT,
                    deleted INT,release_status VARCHAR(16),allowed_business_types JSON)
                    """);
            mall.jdbc.update("""
                    INSERT INTO mall_end_entitlement VALUES ('T','POS','RIGHT','STORE','client',1,0,'GA','["RETAIL"]')
                    """);
            var store = new StoreRef("T","store");
            var mallTx = new TransactionTemplate(new DataSourceTransactionManager(mall.ds));
            var platformTx = new TransactionTemplate(new DataSourceTransactionManager(platform.ds));
            var activation = new JdbcEndActivation(mall.ds,platform.ds,n -> UUID.randomUUID().toString());
            var repo = new JdbcSubscriptionRepository(mall.ds,n -> UUID.randomUUID().toString(),activation);
            var access = new JdbcPlatformEndAccessRepository(platform.ds);
            var period = new Period("period","component","ORDER","plan","v1","PRO",100L,100L,500L,
                    Map.of("RIGHT","POS"),1,0,"TWD","p1",false);
            var segment = new AccessSegment(period.no(),"RIGHT",Instant.ofEpochMilli(100),Instant.ofEpochMilli(600));
            var command = new StoreSnapshot(store,"entity","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1);
            mallTx.executeWithoutResult(tx -> {
                repo.ensureAccount(command,"actor");
                repo.revise(store,"RIGHT","rollback",100L,1,List.of(),List.of(segment),List.of(period),"actor");
                assertTrue(repo.segments(store,"RIGHT").contains(segment));
                tx.setRollbackOnly();
            });
            assertEquals(0,mall.jdbc.queryForObject("SELECT COUNT(*) FROM mall_fulfillment_task",Integer.class));

            // The missing platform store makes activation fail after the mall commits.
            mallTx.executeWithoutResult(tx -> {
                repo.ensureAccount(command,"actor");
                var account = repo.lock(store);
                repo.insert(account,period,1,"actor");
                repo.revise(store,"RIGHT","purchase",100L,1,List.of(),List.of(segment),List.of(period),"actor");
                repo.updateVersion(account,1,"actor");
                assertEquals(0,platform.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_end_grant",Integer.class));
            });
            assertEquals("PENDING",mall.jdbc.queryForObject("SELECT status FROM mall_fulfillment_task",String.class));
            assertEquals(1,mall.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class));
            platform.jdbc.update("""
                    INSERT INTO mch_store(tenant_no,store_no,entity_no,business_type_code)
                    VALUES ('T','store','entity','RETAIL')
                    """);
            mall.jdbc.update("UPDATE mall_fulfillment_task SET next_retry_at=0");
            assertEquals(0,activation.retryPendingAll(10));
            var granted = platformTx.execute(tx -> access.lockAndRead(store,"POS","RIGHT","client"));
            assertEquals(List.of(segment),granted.segments());
            assertThrows(AccessDenied.class, () -> platformTx.execute(tx -> access.lockAndRead(store,"POS","RIGHT","wrong")));

            // Replay an activation whose platform commit outlived its mall acknowledgement.
            mall.jdbc.update("UPDATE mall_fulfillment_task SET status='PENDING'");
            assertEquals(0,activation.retryPendingAll(10));
            assertEquals(1,platform.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_end_grant",Integer.class));
            mallTx.executeWithoutResult(tx -> {
                mall.jdbc.update("""
                        UPDATE mch_subscription_period SET state='FROZEN',effective_end_at=100,revoked_duration_ms=500
                        WHERE tenant_no='T' AND period_no='period'
                        """);
                repo.revise(store,"RIGHT","refund",100L,2,List.of(segment),List.of(),repo.periods(store),"actor");
            });
            var revoked = platformTx.execute(tx -> access.lockAndRead(store,"POS","RIGHT","client"));
            assertTrue(revoked.revoked());
            assertTrue(revoked.segments().isEmpty());
            assertEquals(2,revoked.version());
        }
    }
    static void platformSchema(MysqlDatabase platform) {
        var platformDir = Path.of("../../shop-app/src/test/resources/db/n_platform");
        new ResourceDatabasePopulator(
                new FileSystemResource(platformDir.resolve("V1__platform_design.sql")),
                new FileSystemResource(platformDir.resolve("V2__platform_supplement.sql")),
                new FileSystemResource("../../shop-app/src/main/resources/db/n_platform/V3__mall_end_activation.sql")).execute(platform.ds);
    }
}
