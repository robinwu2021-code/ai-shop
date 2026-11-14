package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueConsumerIssuerServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueIssuerNotificationTest {
 static class Fixture {
  final DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:p5-c-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  final JdbcTemplate jdbc=new JdbcTemplate(ds);final ObjectMapper json=new ObjectMapper();
  final PayRightsDocumentNumbers numbers=mock(PayRightsDocumentNumbers.class);
  final StoredValueConsumerIssuerService service=new StoredValueConsumerIssuerServiceImpl(new IssuerRepository(new DataSourceTransactionManager(ds),json),new StoredValueCursorCodec("p5-c-owned-issuer-references-key-more-than-thirty-two-characters",json),numbers);
  Fixture(){
   jdbc.execute("CREATE TABLE sv_account(tenant_no VARCHAR(32),account_no VARCHAR(64),entity_no VARCHAR(64),store_no VARCHAR(64),currency VARCHAR(8),issuer_no VARCHAR(64),customer_no VARCHAR(64),user_no VARCHAR(64),deleted INT DEFAULT 0)");
   jdbc.execute("CREATE TABLE sv_statement(tenant_no VARCHAR(32),account_no VARCHAR(64),record_no VARCHAR(64),business_no VARCHAR(64),store_no VARCHAR(64),store_name VARCHAR(100),title VARCHAR(100),currency VARCHAR(8),signed_amount_minor BIGINT,occurred_at TIMESTAMP,type VARCHAR(24),deleted INT DEFAULT 0)");
   jdbc.execute("CREATE TABLE sv_customer_identity(tenant_no VARCHAR(32),customer_no VARCHAR(64),user_no VARCHAR(64))");
   jdbc.execute("CREATE TABLE sv_issuer(tenant_no VARCHAR(32),issuer_no VARCHAR(64),current_issuer_version_no VARCHAR(64),scope_type VARCHAR(24),name VARCHAR(100),deleted INT DEFAULT 0)");
   jdbc.execute("CREATE TABLE sv_issuer_version(tenant_no VARCHAR(32),issuer_version_no VARCHAR(64),market_zone VARCHAR(64),deleted INT DEFAULT 0)");
   jdbc.execute("CREATE TABLE ent_usage(tenant_no VARCHAR(32),usage_no VARCHAR(64),operation_no VARCHAR(64),asset_kind VARCHAR(24),status VARCHAR(24),deleted INT DEFAULT 0)");
   jdbc.update("INSERT INTO sv_issuer VALUES('MAIN','BRAND','V1','BRAND','Brand',0)");
   jdbc.update("INSERT INTO sv_issuer_version VALUES('MAIN','V1','Asia/Shanghai',0)");
   jdbc.update("INSERT INTO sv_account VALUES('MAIN','WA','E1','A','CNY',NULL,'PA','UA',0),('MAIN','WB','E1',NULL,'CNY','BRAND','PA','UA',0),('MAIN','WC','E1',NULL,'CNY','BRAND','PB','UB',0)");
   record("WA","RA","R1","A",100,"RECHARGE",1);record("WB","RB","R2","A",130,"RECHARGE",2);record("WB","UC","OP1","B",-50,"REDEMPTION",3);record("WC","RC","R3","B",999,"RECHARGE",4);
   jdbc.update("INSERT INTO ent_usage VALUES('MAIN','USE1','OP1','STORED_VALUE','COMMITTED',0)");
   when(numbers.findAll(any(),anyString(),anyCollection())).thenReturn(Map.of());
   when(numbers.resolve(any(),eq("MAIN"),eq("EU20261024000001"))).thenReturn("USE1");
  }
  void record(String account,String record,String business,String store,long amount,String type,int hour){jdbc.update("INSERT INTO sv_statement VALUES('MAIN',?,?,?,?,?,'Service','CNY',?,?,?,0)",account,record,business,store,"Store "+store,amount,java.sql.Timestamp.from(java.time.Instant.parse("2026-10-24T00:00:00Z").plusSeconds(hour*3600)),type);}
  EntitlementContext user(String user){return new EntitlementContext("MAIN",user,"READ","0".repeat(64));}
 }
 @Test void brandAppearsOnceAndOwnedHistoryIncludesActualOccurrenceStore(){
  var f=new Fixture();var directory=f.service.directory(f.user("UA"),null,20).records();assertThat(directory).hasSize(2);
  assertThat(directory.getFirst().scopeType()).isEqualTo("BRAND");assertThat(directory.getFirst().lastType()).isEqualTo("REDEMPTION");
  var rows=f.service.records(f.user("UA"),directory.getFirst().issuerRef(),"ALL",null,null,null,null,20).records();
  assertThat(rows).hasSize(2);assertThat(rows.getFirst().storeNo()).isEqualTo("B");assertThat(rows.getFirst().signedAmountMinor()).isEqualTo(-50);
  assertThat(rows).noneMatch(r->r.signedAmountMinor()==999);
 }
 @Test void linkLocatesFreshOwnedReferenceAndCannotBeForwardedToAnotherConsumer(){
  var f=new Fixture();var issuer=f.service.locate(f.user("UA"),"EU20261024000001");
  assertThat(f.service.records(f.user("UA"),issuer.issuerRef(),"REDEMPTION",null,null,"EU20261024000001",null,20).records()).hasSize(1);
  assertThatThrownBy(()->f.service.issuer(f.user("UB"),issuer.issuerRef())).isInstanceOf(BizException.class);
  assertThatThrownBy(()->f.service.locate(f.user("UB"),"EU20261024000001")).isInstanceOf(BizException.class);
 }
 @Test void cursorIsConsumerBoundAndClosedStoreHistoryDoesNotNeedNewIssuanceEligibility(){
  var f=new Fixture();var first=f.service.directory(f.user("UA"),null,1);assertThat(first.nextCursor()).isNotNull();
  assertThatThrownBy(()->f.service.directory(f.user("UB"),first.nextCursor(),1)).isInstanceOf(BizException.class);
  assertThat(f.service.directory(f.user("UA"),first.nextCursor(),1).records()).hasSize(1);
  assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_account",Long.class)).isEqualTo(3);
 }
}
