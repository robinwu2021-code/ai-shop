package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoredValueSummaryTest {
 private StoredValueSettlementFixture fixture()throws Exception{
  var f=new StoredValueSettlementFixture();f.jdbc.execute("ALTER TABLE sv_issuer ADD name VARCHAR(64) DEFAULT 'Brand'");f.jdbc.execute("ALTER TABLE sv_issuer ADD write_mode VARCHAR(24) DEFAULT 'ISSUER'");f.jdbc.execute("ALTER TABLE sv_issuer_version ADD market_zone VARCHAR(64) DEFAULT 'Asia/Shanghai'");
  f.jdbc.execute("CREATE TABLE sv_account(tenant_no VARCHAR(32),account_no VARCHAR(64),issuer_no VARCHAR(64),customer_no VARCHAR(64),currency VARCHAR(8),deleted INT)");
  f.jdbc.execute("CREATE TABLE sv_recharge(tenant_no VARCHAR(32),recharge_no VARCHAR(64),issuer_no VARCHAR(64),activity_no VARCHAR(64),source_kind VARCHAR(32),activity_version_no VARCHAR(64),status VARCHAR(24),deleted INT)");
  f.jdbc.execute("CREATE TABLE sv_lot(tenant_no VARCHAR(32),lot_no VARCHAR(64),account_no VARCHAR(64),recharge_no VARCHAR(64),issuer_no VARCHAR(64),release_no VARCHAR(64),recharge_store_no VARCHAR(64),principal_minor BIGINT,bonus_minor BIGINT,held_principal_minor BIGINT,held_bonus_minor BIGINT,deleted INT)");
  f.jdbc.execute("CREATE TABLE sv_voucher(tenant_no VARCHAR(32),voucher_no VARCHAR(64),biz_type VARCHAR(24),occurred_at TIMESTAMP,deleted INT)");
  f.jdbc.execute("CREATE TABLE sv_entry(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32),voucher_no VARCHAR(64),lot_no VARCHAR(64),principal_delta BIGINT,bonus_delta BIGINT,deleted INT)");
  f.jdbc.execute("CREATE TABLE sv_statement(tenant_no VARCHAR(32),voucher_no VARCHAR(64),store_no VARCHAR(64),store_name VARCHAR(64),deleted INT)");
  f.jdbc.execute("CREATE TABLE sv_release(tenant_no VARCHAR(32),issuer_no VARCHAR(64),release_no VARCHAR(64),name VARCHAR(64),deleted INT)");
  f.jdbc.update("INSERT INTO sv_account VALUES('MAIN','A1','I1','P1','CNY',0),('MAIN','A2','I2','P2','CNY',0)");
  f.jdbc.update("INSERT INTO sv_recharge VALUES('MAIN','R1','I1','ACT1','V2_BRAND_ACTIVITY','AV1','CREDITED',0),('MAIN','R2','I1','ACT2','V2_BRAND_ACTIVITY','AV2','CREDITED',0),('MAIN','RX','I2','ACT1','V2_BRAND_ACTIVITY','AV1','CREDITED',0)");
  f.jdbc.update("INSERT INTO sv_lot VALUES('MAIN','L1','A1','R1','I1','REL1','S1',800,240,0,0,0),('MAIN','L2','A1','R2','I1','REL2','S2',400,0,0,0,0),('MAIN','LX','A2','RX','I2','RELX','SX',999,0,0,0,0)");
  voucher(f,"V1","RECHARGE","S1");voucher(f,"V2","RECHARGE","S2");voucher(f,"U1","CONSUME","S2");voucher(f,"VX","RECHARGE","SX");
  f.jdbc.update("INSERT INTO sv_entry(tenant_no,voucher_no,lot_no,principal_delta,bonus_delta,deleted) VALUES('MAIN','V1','L1',1000,300,0),('MAIN','V2','L2',500,0,0),('MAIN','U1','L1',-200,-60,0),('MAIN','U1','L2',-100,0,0),('MAIN','VX','LX',999,0,0)");
  f.jdbc.update("INSERT INTO sv_release VALUES('MAIN','I1','REL1','Activity one',0),('MAIN','I1','REL2','Activity two',0)");return f;
 }
 private void voucher(StoredValueSettlementFixture f,String no,String type,String store){f.jdbc.update("INSERT INTO sv_voucher VALUES('MAIN',?,?,'2026-09-15 01:00:00',0)",no,type);f.jdbc.update("INSERT INTO sv_statement VALUES('MAIN',?,?,?,0)",no,store,store);}
 private Scope scope(String activity){return new Scope("MAIN","MCH:OWNER","M1","I1","BRAND","B1","CNY",activity,null);}
 private Period period(){return new Period(Instant.parse("2026-09-01T00:00:00Z"),Instant.parse("2026-10-01T00:00:00Z"));}
 private StoredValueSummaryService service(StoredValueSettlementFixture f){return new StoredValueSummaryService(f.db,new StoredValueCursorCodec("p6-summary-test-secret-32-characters",f.json));}
 @Test void amountsReconcileWithoutCountingOneMultiLotUseTwiceOrMixingIssuers()throws Exception{
  var f=fixture();var s=service(f).summarize(scope(null),period());assertEquals(1500,s.creditedPaidMinor());assertEquals(300,s.creditedBonusMinor());assertEquals(360,s.consumedMinor());assertEquals(1440,s.closingMinor());assertEquals(2,s.rechargeCount());assertEquals(1,s.usageCount());assertEquals(1,s.customerCount());assertEquals(s.openingMinor()+s.creditedPaidMinor()+s.creditedBonusMinor()-s.consumedMinor()+s.reversedMinor(),s.closingMinor());
 }
 @Test void activityBalanceBelongsToItsOriginalLotsAndStoreAttributionDoesNotCopySharedBalance()throws Exception{
  var f=fixture();var service=service(f);var activity=service.activities(new Query(scope("ACT1"),period(),null,20)).records().getFirst();assertEquals(1040,activity.remainingMinor());assertEquals(200,activity.consumedPrincipalMinor());assertEquals(60,activity.consumedBonusMinor());var stores=service.stores(new Query(scope(null),period(),null,20)).records();assertEquals(2,stores.size());assertEquals(360,stores.stream().filter(s->s.storeNo().equals("S2")).findFirst().orElseThrow().consumedMinor());
 }
 @Test void missingStatementOrUncutoverIssuerCannotBePresentedAsZero()throws Exception{
  var f=fixture();f.jdbc.update("DELETE FROM sv_statement WHERE voucher_no='U1'");assertThrows(ai.neargo.shop.common.BizException.class,()->service(f).summarize(scope(null),period()));
  var other=fixture();other.jdbc.update("UPDATE sv_issuer SET write_mode='LEGACY'");assertThrows(ai.neargo.shop.common.BizException.class,()->service(other).summarize(scope(null),period()));
 }
}
