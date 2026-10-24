package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Period;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StoredValueSettlementBillTest {
 Period september(){return new Period(Instant.parse("2026-09-01T00:00:00Z"),Instant.parse("2026-10-01T00:00:00Z"));}
 Generation command(StoredValueSettlementFixture f,Period period,String key){return new Generation(f.payer(),"I1",period,"CNY",key);}
 @Test void generateBillTwiceConsumesEachClearingOnlyOnce()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("C1","E2",2000,600,2000,"2026-09-15T00:00:00Z","2026-09-14T00:00:00Z");
  var first=f.bills.generate(command(f,september(),"GEN1"));assertThat(first.bills()).hasSize(1);assertThat(first.bills().getFirst().payableMinor()).isEqualTo(2000);assertThat(first.bills().getFirst().bonusMinor()).isEqualTo(600);
  assertThat(f.bills.generate(command(f,september(),"GEN1"))).isEqualTo(first);var other=f.bills.generate(command(f,september(),"GEN2"));assertThat(other.bills()).isEqualTo(first.bills());assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_bill_item",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT payable_minor FROM sv_clearing_entry",Long.class)).isEqualTo(2000);
 }
 @Test void concurrentSamePeriodCannotClaimOneClearingTwice()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("C1","E2",2000,600,2000,"2026-09-15T00:00:00Z","2026-09-14T00:00:00Z");var pool=Executors.newFixedThreadPool(2);try{
   var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return f.bills.generate(command(f,september(),"G1"));});var b=pool.submit(()->{gate.await();return f.bills.generate(command(f,september(),"G2"));});gate.countDown();assertThat(a.get(15,TimeUnit.SECONDS).bills()).hasSize(1);assertThat(b.get(15,TimeUnit.SECONDS).bills()).hasSize(1);
  }finally{pool.shutdownNow();}assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_bill",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_bill_item",Long.class)).isEqualTo(1);
 }
 @Test void postingAfterTheFrozenWatermarkCarriesOldBusinessIntoTheNextPeriod()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("C1","E2",100,30,100,"2026-09-15T00:00:00Z","2026-09-14T00:00:00Z");f.bills.generate(command(f,september(),"G1"));
  f.clearing("LATE","E2",200,60,200,"2026-09-29T00:00:00Z","2026-09-10T00:00:00Z");f.clearing("NEW","E2",300,90,300,"2026-10-05T00:00:00Z","2026-10-04T00:00:00Z");
  var next=f.bills.generate(command(f,new Period(Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-15T00:00:00Z")),"G2"));assertThat(next.bills()).hasSize(1);assertThat(next.bills().getFirst().payableMinor()).isEqualTo(500);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_bill_item",Long.class)).isEqualTo(3);
 }
 @Test void sameEntityIsInternalReconciliationAndNeverCreatesFakePayment()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("C1","E1",2000,600,0,"2026-09-15T00:00:00Z","2026-09-14T00:00:00Z");var bill=f.bills.generate(command(f,september(),"G")).bills().getFirst();assertThat(bill.billKind()).isEqualTo("INTERNAL_RECON");assertThat(bill.payableMinor()).isZero();
  assertThatThrownBy(()->f.payments.register(new Registration(f.payer(),bill.billNo(),0,1,Instant.parse("2026-10-02T00:00:00Z"),"CASH","R1",java.util.List.of(),"PAY"))).isInstanceOf(BizException.class);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_payment",Long.class)).isZero();
 }
 @Test void aNetNegativeAdjustmentCreatesACreditDirectionWithoutNegativePayable()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("ADJ","E2",0,0,-100,"2026-09-15T00:00:00Z","2026-09-14T00:00:00Z");var bill=f.bills.generate(command(f,september(),"G")).bills().getFirst();assertThat(bill.payerEntityNo()).isEqualTo("E2");assertThat(bill.payeeEntityNo()).isEqualTo("E1");assertThat(bill.payableMinor()).isEqualTo(100);assertThat(f.jdbc.queryForObject("SELECT amount_minor FROM sv_settlement_bill_item",Long.class)).isEqualTo(100);
 }
 @Test void unclaimedOlderPartyCarriesForwardAfterAnotherPartyGeneratedFirst()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("A","E2",100,30,100,"2026-09-10T00:00:00Z","2026-09-10T00:00:00Z");f.clearing("B","E3",200,60,200,"2026-09-11T00:00:00Z","2026-09-11T00:00:00Z");
  var first=f.bills.generate(new Generation(f.actor("MCH:A","USER:A","E2"),"I1",september(),"CNY","A-GEN"));assertThat(first.bills()).hasSize(1);
  var next=f.bills.generate(new Generation(f.actor("MCH:B","USER:B","E3"),"I1",new Period(Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-15T00:00:00Z")),"CNY","B-GEN"));assertThat(next.bills()).hasSize(1);assertThat(next.bills().getFirst().payableMinor()).isEqualTo(200);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_bill_item",Long.class)).isEqualTo(2);
 }
 @Test void originalGenerationReplayCannotExposeBillsAfterPartyAuthorityIsRevoked()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("A","E2",100,30,100,"2026-09-10T00:00:00Z","2026-09-10T00:00:00Z");assertThat(f.bills.generate(command(f,september(),"ORIGINAL")).bills()).hasSize(1);
  var reduced=f.actor("MCH:PAYER","USER:PAYER","E3");assertThat(f.bills.generate(new Generation(reduced,"I1",september(),"CNY","ORIGINAL")).bills()).isEmpty();assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_bill",Long.class)).isEqualTo(1);
 }
}
