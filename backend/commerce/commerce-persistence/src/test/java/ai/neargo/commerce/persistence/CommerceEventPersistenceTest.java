package ai.neargo.commerce.persistence;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.port.*;
import java.time.Instant;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class CommerceEventPersistenceTest {
 CommerceEvent event(String tenant,String payload){return assertDoesNotThrow(()->CommerceEvent.class.getConstructor(String.class,String.class,String.class,String.class,long.class,String.class,int.class,String.class,Instant.class).newInstance("e1",tenant,"SUBSCRIPTION","s1",9L,"CHANGED",2,payload,Instant.ofEpochMilli(1234567)));}
 InboundFact fact(String tenant,String hash){return assertDoesNotThrow(()->InboundFact.class.getConstructor(String.class,String.class,String.class,String.class,String.class,String.class,Instant.class).newInstance(tenant,"PAYMENT","f1","PAID","o1",hash,Instant.ofEpochMilli(1234567)));}
 CommerceEventPersistencePort adapter(MysqlDatabase db){return assertDoesNotThrow(()->(CommerceEventPersistencePort)Class.forName("ai.neargo.commerce.persistence.JdbcCommerceEventPersistence").getConstructor(javax.sql.DataSource.class).newInstance(db.ds));}
 @Test void eventVersionAndInboundIdentityAreExplicitTypedFields(){assertNotNull(event("A","{}"));assertNotNull(fact("A","hash"));}
 @Test void tenantDedupConflictAndRollbackUseSameTransaction(){try(var db=new MysqlDatabase()){db.migrate();var port=adapter(db);var u=new JdbcCommerceUnitOfWork(db.ds,new DataSourceTransactionManager(db.ds));
  assertThrows(IllegalStateException.class,()->port.append(event("A","{}")));
  u.required(()->{assertTrue(port.append(event("A","{\"v\":1}")));assertFalse(port.append(event("A","{\"v\":1}")));assertTrue(port.append(event("B","{}")));assertTrue(port.recordInbound(fact("A","hash")));assertFalse(port.recordInbound(fact("A","hash")));assertTrue(port.recordInbound(fact("B","hash")));return null;});
  assertThrows(IllegalStateException.class,()->u.required(()->port.append(event("A","{\"v\":2}"))));
  assertThrows(IllegalStateException.class,()->u.required(()->port.recordInbound(fact("A","changed"))));
  assertThrows(IllegalStateException.class,()->u.required(()->{db.jdbc.execute(Rows.mch_subscription_period("C"));port.append(event("C","{}"));port.recordInbound(fact("C","hash"));throw new IllegalStateException("rollback");}));
  assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox WHERE tenant_no='C'",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_event_inbox WHERE tenant_no='C'",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_subscription_period WHERE tenant_no='C'",Integer.class));
  var page=port.pending(new EventQuery("A",null,1));assertEquals(java.util.List.of(event("A","{\"v\": 1}")),page.events());assertFalse(page.hasMore());
  u.required(()->{port.markPublished("A","e1",Instant.ofEpochMilli(555));return null;});assertTrue(port.pending(new EventQuery("A",null,1)).events().isEmpty());assertEquals(1,port.pending(new EventQuery("B",null,1)).events().size());
 }}
}
