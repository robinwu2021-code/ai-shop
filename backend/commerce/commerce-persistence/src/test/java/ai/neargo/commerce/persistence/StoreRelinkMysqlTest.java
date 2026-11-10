package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.commerce.core.subscription.StoreRelinkService;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class StoreRelinkMysqlTest {
    private static final StoreRef STORE=SubscriptionMysqlTest.STORE;
    private static final StoreRelinkFacade.Command COMMAND=new StoreRelinkFacade.Command(STORE,"entity","target","claim-1");
    private StoreRelinkService service(MysqlDatabase db) {
        return new StoreRelinkService(new JdbcStoreRelinkRepository(db.ds), command -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("claim-1",command.changeNo());
        });
    }
    private JdbcCatalogFixture free(MysqlDatabase db) {
        var fixture=new SubscriptionMysqlTest();
        var catalog=fixture.setup(db);
        var sources=fixture.services(db,catalog,SubscriptionMysqlTest.NOV1);
        catalog.uow.required(()->sources.create().initializeFree(SubscriptionMysqlTest.OWNER,fixture.init()));
        return catalog;
    }
    private String entity(MysqlDatabase db,String table) {
        return db.jdbc.queryForObject("SELECT entity_no FROM "+table+" WHERE tenant_no=? AND store_no=? LIMIT 1",String.class,STORE.tenantNo(),STORE.storeNo());
    }
    @Test void trustedOuterTransactionMovesFreeAccountAndPeriodTogetherAndRollsBackTogether() {
        try(var db=new MysqlDatabase()) {
            var catalog=free(db); var service=service(db);
            assertTrue(service.inspect(STORE).accountPresent());
            assertFalse(service.inspect(STORE).hasPaidHistory());
            assertThrows(IllegalStateException.class,()->service.relinkFree(COMMAND));
            assertThrows(IllegalArgumentException.class,()->catalog.uow.required(()->{
                service.relinkFree(COMMAND); throw new IllegalArgumentException("rollback after relink");
            }));
            assertEquals("entity",entity(db,"mch_subscription_account"));
            assertEquals("entity",entity(db,"mch_subscription_period"));
            catalog.uow.required(()->{service.relinkFree(COMMAND);return null;});
            assertEquals("target",entity(db,"mch_subscription_account"));
            assertEquals("target",entity(db,"mch_subscription_period"));
            assertThrows(IllegalStateException.class,()->catalog.uow.required(()->{service.relinkFree(COMMAND);return null;}));
        }
    }
    @Test void expiredRevokedPaidPeriodsStillBlockAndUnknownSchemaIsNotClean() {
        try(var db=new MysqlDatabase()) {
            var catalog=free(db); var service=service(db);
            db.jdbc.update("UPDATE mch_subscription_period SET tier_code='PRO',state='REVOKED',effective_end_at=1");
            assertTrue(service.inspect(STORE).hasPaidHistory());
            assertThrows(IllegalStateException.class,()->catalog.uow.required(()->{service.relinkFree(COMMAND);return null;}));
            assertEquals("entity",entity(db,"mch_subscription_account"));
            db.jdbc.execute("DROP TABLE mall_refund");
            assertThrows(org.springframework.dao.DataAccessException.class,()->service.inspect(STORE));
        }
    }
    @Test void noAccountLegacyNeedsEveryOwnedHistoryEmptyAndDoesNotInventAnAccount() {
        try(var db=new MysqlDatabase()) {
            db.migrate(); var service=service(db);
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.ds);
            var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
            assertFalse(service.inspect(STORE).accountPresent());
            transaction.execute(status->{service.relinkFree(COMMAND);return null;});
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_account",Integer.class));
        }
    }
    private void credit(MysqlDatabase db) {
        db.jdbc.update("""
                INSERT INTO mch_subscription_credit_account(tenant_no,store_no,currency,balance_minor,reserved_minor,created_at,updated_at,version,deleted)
                VALUES(?,?,'CNY',0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
                """,STORE.tenantNo(),STORE.storeNo());
    }
    @Test void concurrentPriorCreditWriterWinsAccountLockAndRelinkSeesItsCommittedHistory() throws Exception {
        try(var db=new MysqlDatabase()) {
            var catalog=free(db);
            var attempting=new CountDownLatch(1);
            var service=new StoreRelinkService(new JdbcStoreRelinkRepository(db.ds),command->attempting.countDown());
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var writer=pool.submit(()->catalog.uow.required(()->{
                    new JdbcSubscriptionAccountRepository(db.ds).lockSubscriptionAccount(STORE);
                    credit(db);locked.countDown();
                    try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) {throw new AssertionError(e);}
                    return null;
                }));
                assertTrue(locked.await(10,TimeUnit.SECONDS));
                var claim=pool.submit(()->assertThrows(IllegalStateException.class,()->catalog.uow.required(()->{service.relinkFree(COMMAND);return null;})));
                assertTrue(attempting.await(10,TimeUnit.SECONDS));
                assertFalse(claim.isDone(), "claim must wait for the subscription-account writer");
                release.countDown();writer.get(15,TimeUnit.SECONDS);claim.get(15,TimeUnit.SECONDS);
                assertEquals("entity",entity(db,"mch_subscription_account"));
                assertTrue(service.inspect(STORE).hasPaidHistory());
            }
        }
    }
}
