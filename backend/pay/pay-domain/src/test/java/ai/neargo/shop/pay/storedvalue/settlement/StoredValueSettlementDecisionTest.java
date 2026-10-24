package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Period;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoredValueSettlementDecisionTest {
 private static final Period PERIOD=new Period(Instant.parse("2026-09-01T00:00:00Z"),Instant.parse("2026-10-01T00:00:00Z"));
 private StoredValueSettlementDecisionService decisions(StoredValueSettlementFixture f){return new StoredValueSettlementDecisionService(f.repo,f.audit,f.numbers,f.commands);}
 private Bill bill(StoredValueSettlementFixture f){f.clearing("C1","E2",1000,300,1000,"2026-09-10T00:00:00Z","2026-09-10T00:00:00Z");return f.bills.generate(new Generation(f.payer(),"I1",PERIOD,"CNY","GEN")).bills().getFirst();}
 @Test void bothPartiesMustReconcileAndRepeatedKeyDoesNotDuplicateEvent()throws Exception{
  var f=new StoredValueSettlementFixture();var d=decisions(f);var b=bill(f);
  var cmd=new Decision(f.payer(),b.billNo(),b.revision(),"PAYER",null,"PAYER-CONFIRM");var half=d.reconcile(cmd);assertEquals("DRAFT",half.status());assertEquals(half,d.reconcile(cmd));
  var ready=d.reconcile(new Decision(f.payee(),b.billNo(),half.revision(),"PAYEE",null,"PAYEE-CONFIRM"));assertEquals("RECONCILED",ready.status());assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_settlement_event",Integer.class));
 }
 @Test void wrongEntityAndStaleRevisionCannotChangeBill()throws Exception{
  var f=new StoredValueSettlementFixture();var d=decisions(f);var b=bill(f);
  assertThrows(BizException.class,()->d.reconcile(new Decision(f.payee(),b.billNo(),b.revision(),"PAYER",null,"WRONG-PARTY")));
  d.reconcile(new Decision(f.payer(),b.billNo(),b.revision(),"PAYER",null,"RIGHT"));assertThrows(BizException.class,()->d.dispute(new Decision(f.payee(),b.billNo(),b.revision(),null,"stale","STALE")));
 }
 @Test void adjustmentBeforePaymentInvalidatesBothReconciliationsWithoutChangingConsumption()throws Exception{
  var f=new StoredValueSettlementFixture();var d=decisions(f);var b=bill(f);b=d.reconcile(new Decision(f.payer(),b.billNo(),b.revision(),"PAYER",null,"P"));b=d.reconcile(new Decision(f.payee(),b.billNo(),b.revision(),"PAYEE",null,"Q"));
  long watermark=b.sourceWatermark();var adjusted=d.adjust(new Adjustment(f.payer(),b.billNo(),"C1",b.revision(),-200,"agreed correction","ADJUST"));
  assertEquals("DRAFT",adjusted.status());assertEquals(800,adjusted.payableMinor());assertNull(adjusted.payerReconciledBy());assertNull(adjusted.payeeReconciledBy());assertEquals(watermark,adjusted.sourceWatermark());
  assertEquals(1000,f.jdbc.queryForObject("SELECT principal_used_minor FROM sv_clearing_entry WHERE clearing_no='C1'",Long.class));assertEquals(0,f.jdbc.queryForObject("SELECT principal_used_minor FROM sv_clearing_entry WHERE entry_type='ADJUSTMENT'",Long.class));
 }
 @Test void disputeResolutionMatchesOriginalDisputeAndKeepsActualPaymentFacts()throws Exception{
  var f=new StoredValueSettlementFixture();var d=decisions(f);f.bill(1000);var disputed=d.dispute(new Decision(f.payee(),"BILL1",0,null,"receipt missing","DISPUTE"));
  assertThrows(BizException.class,()->d.resolve(new Resolution(f.payer(),"BILL1",disputed.revision(),"OTHER","REJECT","wrong dispute","BAD")));
  var resolved=d.resolve(new Resolution(f.payee(),"BILL1",disputed.revision(),disputed.disputeNo(),"ACCEPT","evidence received","RESOLVE"));assertEquals("RECONCILED",resolved.status());assertEquals(0,resolved.confirmedReceiptMinor());assertNull(resolved.disputeNo());
 }
 @Test void settledAdjustmentEntersFuturePeriodAndNeverRewritesSettledBill()throws Exception{
  var f=new StoredValueSettlementFixture();var d=decisions(f);var b=bill(f);b=d.reconcile(new Decision(f.payer(),b.billNo(),b.revision(),"PAYER",null,"P"));b=d.reconcile(new Decision(f.payee(),b.billNo(),b.revision(),"PAYEE",null,"Q"));
  var p=f.payments.register(new Registration(f.payer(),b.billNo(),b.revision(),1000,Instant.parse("2026-10-02T00:00:00Z"),"TRANSFER","BANK1",java.util.List.of(),"PAY"));f.payments.confirm(new Receipt(f.payee(),p.paymentNo(),p.revision(),Instant.parse("2026-10-03T00:00:00Z"),"RECEIVE"));
  var current=f.repo.bill(f.payer(),b.billNo(),false);var corrected=d.adjust(new Adjustment(f.payer(),b.billNo(),"C1",((Number)current.get("version")).longValue(),-200,"settled correction","ADJUST"));assertEquals("SETTLED",corrected.status());assertEquals(1000,corrected.payableMinor());assertEquals(1,corrected.sourceCount());
  assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_clearing_entry c LEFT JOIN sv_settlement_bill_item i ON i.clearing_no=c.clearing_no WHERE c.entry_type='ADJUSTMENT' AND i.item_no IS NULL",Integer.class));
 }
 @Test void correctingUseOnReverseCreditBillConvertsDeltaIntoBillDirection()throws Exception{
  var f=new StoredValueSettlementFixture();f.clearing("OLD-ADJ","E2",0,0,-1000,"2026-09-10T00:00:00Z","2026-08-10T00:00:00Z");f.clearing("USE","E2",100,30,100,"2026-09-11T00:00:00Z","2026-09-11T00:00:00Z");var b=f.bills.generate(new Generation(f.payer(),"I1",PERIOD,"CNY","REVERSE")).bills().getFirst();assertEquals("E2",b.payerEntityNo());assertEquals(900,b.payableMinor());
  var adjusted=decisions(f).adjust(new Adjustment(f.payer(),b.billNo(),"USE",b.revision(),-50,"reduce original use","REVERSE-ADJUST"));assertEquals(950,adjusted.payableMinor());assertEquals(-50,f.jdbc.queryForObject("SELECT payable_minor FROM sv_clearing_entry WHERE original_clearing_no='USE'",Long.class));assertEquals(50,f.jdbc.queryForObject("SELECT i.amount_minor FROM sv_settlement_bill_item i JOIN sv_clearing_entry c ON c.clearing_no=i.clearing_no WHERE c.original_clearing_no='USE'",Long.class));
 }
}
