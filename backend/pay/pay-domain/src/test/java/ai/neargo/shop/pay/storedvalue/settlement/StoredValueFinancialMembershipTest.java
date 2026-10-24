package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueFinancialMembershipService;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueFinancialMembershipTest {
 private StoredValueSettlementFixture fixture()throws Exception{
  var f=new StoredValueSettlementFixture();f.jdbc.execute("ALTER TABLE sv_issuer ADD write_mode VARCHAR(24) DEFAULT 'ISSUER'");f.ddl(f.file("V576__stored_value_financial_membership_saga.sql"));String ddl=f.file("V548__stored_value_issuer_foundation.sql");int start=ddl.indexOf("CREATE TABLE sv_issuer_store_access");f.ddl(ddl.substring(start,ddl.indexOf(';',start)+1));
  f.jdbc.execute("CREATE TABLE sv_account(tenant_no VARCHAR(32),issuer_no VARCHAR(64),principal_minor BIGINT,bonus_minor BIGINT,held_principal_minor BIGINT,held_bonus_minor BIGINT,deleted INT)");f.jdbc.execute("CREATE TABLE sv_recharge(tenant_no VARCHAR(32),issuer_no VARCHAR(64),recharge_no VARCHAR(64),status VARCHAR(32),deleted INT)");
  f.jdbc.update("INSERT INTO sv_account VALUES('MAIN','I1',1000,300,0,0,0)");access(f,"S1","BIND1");access(f,"S2","BIND2");when(f.readiness.schemaReady()).thenReturn(true);return f;
 }
 private void access(StoredValueSettlementFixture f,String store,String binding){var row=f.repo.base(f.payer());row.put("access_no","ACCESS"+store);row.put("issuer_no","I1");row.put("store_no",store);row.put("operating_entity_no","E1");row.put("source_binding_no",binding);row.put("source_revision",0);row.put("can_issue",1);row.put("can_redeem",1);row.put("status","ACTIVE");row.put("effective_from",java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));f.db.insert("sv_issuer_store_access",row);}
 private Prepare leave(String binding){return new Prepare("MAIN","MCH:OWNER","M1","CHANGE1","S1","E1",List.of(new Side("I1","IV1","B1","LEAVE",binding,null,0,1)),2);}
 @Test void prepareDisablesNewExecutionAndOriginalResumeDoesNotMoveMoney()throws Exception{
  var f=fixture();var service=new StoredValueFinancialMembershipService(f.db,f.readiness);var c=leave("BIND1");var ack=service.prepare(c);assertEquals("PREPARED",ack.state());assertEquals("PREPARING",f.jdbc.queryForObject("SELECT status FROM sv_issuer_store_access WHERE store_no='S1'",String.class));assertEquals(ack,service.prepare(c));
  when(f.readiness.schemaReady()).thenReturn(false);var committed=service.commit(new Commit(c,ack.snapshotHash(),c.changeNo()));assertEquals("ACTIVE",committed.state());assertEquals(committed,service.commit(new Commit(c,ack.snapshotHash(),c.changeNo())));assertEquals("ENDED",f.jdbc.queryForObject("SELECT status FROM sv_issuer_store_access WHERE store_no='S1'",String.class));assertEquals(1300,f.jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_financial_membership_change",Integer.class));
 }
 @Test void lastAcceptingStoreWithFundsCannotExit()throws Exception{
  var f=fixture();f.jdbc.update("UPDATE sv_issuer_store_access SET status='ENDED',can_redeem=0 WHERE store_no='S2'");var service=new StoredValueFinancialMembershipService(f.db,f.readiness);assertThrows(BizException.class,()->service.prepare(leave("BIND1")));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT status FROM sv_issuer_store_access WHERE store_no='S1'",String.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_financial_membership_change",Integer.class));
 }
 @Test void heldOrReceivedUnknownCommitmentBlocksSwitch()throws Exception{
  var f=fixture();var service=new StoredValueFinancialMembershipService(f.db,f.readiness);f.jdbc.update("UPDATE sv_account SET held_principal_minor=1");assertThrows(BizException.class,()->service.prepare(leave("BIND1")));f.jdbc.update("UPDATE sv_account SET held_principal_minor=0");f.jdbc.update("INSERT INTO sv_recharge VALUES('MAIN','I1','ORIGINAL','RECEIVED',0)");assertThrows(BizException.class,()->service.prepare(leave("BIND1")));assertEquals("RECEIVED",f.jdbc.queryForObject("SELECT status FROM sv_recharge",String.class));
 }
 @Test void staleBindingOrForgedCommitCannotActivateAccess()throws Exception{
  var f=fixture();var service=new StoredValueFinancialMembershipService(f.db,f.readiness);assertThrows(BizException.class,()->service.prepare(leave("OTHER")));var c=leave("BIND1");service.prepare(c);assertThrows(BizException.class,()->service.commit(new Commit(c,"a".repeat(64),c.changeNo())));assertEquals("PREPARING",f.jdbc.queryForObject("SELECT status FROM sv_issuer_store_access WHERE store_no='S1'",String.class));
 }
}
