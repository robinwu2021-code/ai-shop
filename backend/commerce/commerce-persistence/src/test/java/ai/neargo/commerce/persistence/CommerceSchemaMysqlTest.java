package ai.neargo.commerce.persistence;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.dao.DuplicateKeyException;
class CommerceSchemaMysqlTest {
 @Test void ownsThirtyTwoTablesAndIndependentHistory() {try(var db=new MysqlDatabase()){db.migrate();assertEquals(33,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",Integer.class,db.name));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=?",Integer.class,db.name));}}
 @Test void sourceConversionAndEventIdentitiesAreTenantScoped() {try(var db=new MysqlDatabase()){db.migrate();
  for(var factory:java.util.List.<java.util.function.Function<String,String>>of(Rows::mch_subscription_period,Rows::mch_subscription_conversion_item,Rows::commerce_event_inbox,Rows::commerce_outbox)){
   db.jdbc.execute(factory.apply("A"));assertThrows(DuplicateKeyException.class,()->db.jdbc.execute(factory.apply("A")));db.jdbc.execute(factory.apply("B"));
  }
 }}
 @Test void creditBalanceCannotBeOverReserved() {try(var db=new MysqlDatabase()){db.migrate();db.jdbc.execute(Rows.mch_subscription_credit_account("A"));assertThrows(org.springframework.dao.DataAccessException.class,()->db.jdbc.update("UPDATE mch_subscription_credit_account SET reserved_minor=1 WHERE tenant_no='A'"));}}
 @Test void everyCandidateFieldHasTypedPhysicalOrExplicitExternalMapping() throws Exception {try(var db=new MysqlDatabase()){db.migrate();
  assertEquals(14,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"sys_business_type"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"sys_business_end"));
  assertEquals(15,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"sys_industry_plan"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"sys_industry_plan_version"));
  assertEquals(12,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"sys_plan_version_end"));
  assertEquals(17,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_product"));
  assertEquals(14,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_sku"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_sku_version"));
  assertEquals(20,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_sku_component"));
  assertEquals(30,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_quote"));
  assertEquals(31,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_order"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_order_item"));
  assertEquals(20,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_order_component"));
  assertEquals(17,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_payment_ref"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_fulfillment_task"));
  assertEquals(17,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_shipment"));
  assertEquals(21,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_refund"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mall_refund_item"));
  assertEquals(16,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"commerce_event_inbox"));
  assertEquals(15,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_account"));
  assertEquals(35,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_period"));
  assertEquals(21,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_store_end_grant"));
  assertEquals(19,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_reservation"));
  assertEquals(20,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_conversion"));
  assertEquals(14,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_conversion_item"));
  assertEquals(12,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_credit_account"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_credit_lot"));
  assertEquals(21,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_credit_ledger"));
  assertEquals(20,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"commerce_outbox"));
  assertEquals(18,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_subscription_migration_map"));
  assertEquals(19,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"mch_end_schedule_event"));
  assertEquals(28,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=?",Integer.class,db.name,"commerce_expiry_notice"));

 }}
}
