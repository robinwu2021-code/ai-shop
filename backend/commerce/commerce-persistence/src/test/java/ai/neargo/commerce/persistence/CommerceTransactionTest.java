package ai.neargo.commerce.persistence;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;
class CommerceTransactionTest {
 Object uow(MysqlDatabase db) {return assertDoesNotThrow(()->Class.forName("ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork").getConstructor(javax.sql.DataSource.class,org.springframework.transaction.PlatformTransactionManager.class).newInstance(db.ds,new DataSourceTransactionManager(db.ds)));}
 Object required(Object u,Supplier<?> fn){try{return u.getClass().getMethod("required",Supplier.class).invoke(u,fn);}catch(java.lang.reflect.InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(Exception e){throw new RuntimeException(e);}}
 @Test void rollbackSourceSegmentCreditAndOutboxWithHostWork() {try(var db=new MysqlDatabase()){db.migrate();Object u=uow(db);db.jdbc.execute("CREATE TABLE host_work(id INT)");
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(new DataSourceTransactionManager(db.ds)).execute(s->{db.jdbc.execute("INSERT INTO host_work VALUES(1)");required(u,()->{db.jdbc.execute(Rows.mch_subscription_period("A"));db.jdbc.execute(Rows.mch_store_end_grant("A"));db.jdbc.execute(Rows.mch_subscription_credit_account("A"));db.jdbc.execute(Rows.commerce_outbox("A"));throw new IllegalStateException("after outbox");});return null;}));
  for(String table:java.util.List.of("host_work","mch_subscription_period","mch_store_end_grant","mch_subscription_credit_account","commerce_outbox"))assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
 }}
 @Test void commitsAllParticipantsAndJoinsOuterRollback() {try(var db=new MysqlDatabase()){db.migrate();Object u=uow(db);required(u,()->{db.jdbc.execute(Rows.mch_subscription_period("A"));db.jdbc.execute(Rows.mch_store_end_grant("A"));db.jdbc.execute(Rows.mch_subscription_credit_account("A"));db.jdbc.execute(Rows.commerce_outbox("A"));return 7;});
 for(String table:java.util.List.of("mch_subscription_period","mch_store_end_grant","mch_subscription_credit_account","commerce_outbox"))assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
 new TransactionTemplate(new DataSourceTransactionManager(db.ds)).execute(s->{required(u,()->{db.jdbc.execute(Rows.mch_subscription_period("B"));return null;});s.setRollbackOnly();return null;});assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period",Integer.class));}}
 @Test void lockingReadReturnsCurrentVersionDespiteEarlierRepeatableReadSnapshot() {try(var db=new MysqlDatabase()){db.migrate();db.jdbc.execute(Rows.mch_subscription_account("A"));Object u=uow(db);
  required(u,()->{assertEquals(0L,db.jdbc.queryForObject("SELECT entitlement_version FROM mch_subscription_account WHERE tenant_no='A'",Long.class));
   var future=java.util.concurrent.CompletableFuture.runAsync(()->db.jdbc.update("UPDATE mch_subscription_account SET entitlement_version=9 WHERE tenant_no='A'"));future.join();
   Object repo=assertDoesNotThrow(()->Class.forName("ai.neargo.commerce.persistence.JdbcSubscriptionAccountRepository").getConstructor(javax.sql.DataSource.class).newInstance(db.ds));
   Object snapshot=assertDoesNotThrow(()->repo.getClass().getMethod("lockSubscriptionAccount",ai.neargo.commerce.api.common.StoreRef.class).invoke(repo,new ai.neargo.commerce.api.common.StoreRef("A","x")));
   assertEquals(9L,assertDoesNotThrow(()->snapshot.getClass().getMethod("entitlementVersion").invoke(snapshot)));return null;});
 }}
}
