package ai.neargo.commerce.core;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.port.OfflineCashRefundPort.*;
import ai.neargo.commerce.core.offline.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OfflineCashRefundTest {
    final Instant now=Instant.parse("2026-11-11T00:00:00Z");
    final Target target=new Target(new StoreRef("MAIN","S1"),"O1",false);
    final Map<String,CashRefundRepository.Refund> records=new HashMap<>();
    boolean tx,cut,wrongFact,pending,closedNative; int payouts;long received=19800,returned;
    final CashRefundRepository repo=new CashRefundRepository(){
        public Optional<Refund> find(String t,String n,boolean lock){return Optional.ofNullable(records.get(n)).filter(r->r.target().store().tenantNo().equals(t));}
        public List<Refund> list(String t,String o){return records.values().stream().filter(r->r.target().orderNo().equals(o)&&r.target().store().tenantNo().equals(t)).toList();}
        public List<String> pending(String t,String after,int limit){return records.values().stream().filter(r->Set.of("PREPARED","PAYOUT_ACCEPTED").contains(r.status())).map(r->r.command().refundNo()).sorted().toList();}
        public void save(Refund r){assertTrue(tx);records.put(r.command().refundNo(),r);}
    };
    final CommerceUnitOfWork uow=new CommerceUnitOfWork(){public <T>T required(java.util.function.Supplier<T> work){boolean old=tx;tx=true;try{return work.get();}finally{tx=old;}}public void requireOutsideTransaction(){assertFalse(tx);}};
    final OfflineCollectionPort money=new OfflineCollectionPort(){
        public List<CashReceipt> receipts(String t,String o){assertFalse(tx);return List.of(new CashReceipt("PAY","U1","PM",new Money("TWD",received),returned,pending,true,closedNative));}
        public void payout(RefundPayout p){assertFalse(tx);assertEquals(now,p.confirmedAt());if(cut)throw new IllegalStateException("cut");if(p.actualAmount().minor()!=received)throw new IllegalArgumentException("OFFLINE_PAYOUT_AMOUNT_MISMATCH");returned=received;payouts++;}
    };
    final PlatformPaymentPort platform=new PlatformPaymentPort(){
        public PaymentOutcome create(PaymentIntentCommand c){throw new UnsupportedOperationException();}public PaymentOutcome query(PaymentQuery q){throw new UnsupportedOperationException();}public PaymentOutcome close(PaymentQuery q){throw new UnsupportedOperationException();}
        public PaymentRefundOutcome refund(PaymentRefundCommand c){assertFalse(tx);assertEquals(received,c.amount().minor());return queryRefund(new PaymentRefundQuery(c.tenantNo(),c.orderNo(),c.refundNo()));}
        public PaymentRefundOutcome queryRefund(PaymentRefundQuery q){assertFalse(tx);var c=records.get(q.refundNo()).payment();
            var f=returned==received?new VerifiedRefundFact("MAIN","RF1","O1",q.refundNo(),target.store(),"U1",wrongFact?"OTHER":"PAY","PM",new Money("TWD",received),c.amount(),"OUT-BANK",AsyncStatus.SUCCEEDED,true,now,"ledger:RF1"):null;
            return new PaymentRefundOutcome(q.refundNo(),f==null?AsyncStatus.PENDING:AsyncStatus.SUCCEEDED,Optional.of("OUT-BANK"),Optional.empty(),f);}
    };
    OfflineCashRefundPort service(){return new DefaultOfflineCashRefundPort(repo,uow,money,platform,Clock.fixed(now,ZoneOffset.UTC));}
    Request request(){return new Request("cash-R1","PAY","wrong amount, return to payer");}
    Payout proof(String key){return new Payout(key,new Money("TWD",received),now,"verified original payer","OUT-BANK","private/refund.jpg");}
    @Test void amountComesFromActualReceiptAndRequestNeverMeansReturned(){var api=service();assertEquals("AWAITING_PAYOUT",api.request(target,"OPS",request()).status());assertEquals(19800,api.list(target).getFirst().amount().minor());assertEquals(0,payouts);assertEquals("REFUNDED",api.payout(target,"OPS","cash-R1",proof("A1")).status());assertEquals("REFUNDED",api.payout(target,"OPS","cash-R1",proof("A1")).status());assertEquals(1,payouts);}
    @Test void unknownPayoutRecoversOriginalIntentAndCannotBeReplaced(){var api=service();api.request(target,"OPS",request());cut=true;assertThrows(IllegalStateException.class,()->api.payout(target,"OPS","cash-R1",proof("A1")));assertEquals("PAYOUT_ACCEPTED",api.list(target).getFirst().status());assertThrows(RuntimeException.class,()->api.payout(target,"OPS","cash-R1",proof("A2")));cut=false;assertEquals("REFUNDED",api.recover(target,"cash-R1").status());}
    @Test void aSucceededButUnrelatedRefundCannotComplete(){var api=service();api.request(target,"OPS",request());wrongFact=true;assertThrows(RuntimeException.class,()->api.payout(target,"OPS","cash-R1",proof("A1")));assertEquals("PAYOUT_ACCEPTED",api.list(target).getFirst().status());}
    @Test void pendingCapacityAndWrongScopeCannotCreateRefund(){pending=true;assertThrows(RuntimeException.class,()->service().request(target,"OPS",request()));pending=false;service().request(target,"OPS",request());assertThrows(RuntimeException.class,()->service().recover(new Target(new StoreRef("MAIN","S2"),"O1",true),"cash-R1"));}
    @Test void nativeLateRefundAdoptionPreservesPayoutActionAcrossUnknown(){
        closedNative=true;var api=service();var command=new PaymentRefundCommand("MAIN","O1","late-O1-0",new Money("TWD",received),"late return",target.store(),"U1","PAY","PM",new Money("TWD",received));
        assertEquals("AWAITING_PAYOUT",api.adopt(target,"OPS",command).status());cut=true;assertThrows(IllegalStateException.class,()->api.payout(target,"OPS","late-O1-0",proof("LATE-A")));cut=false;
        var result=api.recover(target,"late-O1-0");assertEquals("REFUNDED",result.status());assertEquals("APPLIED",result.actions().getFirst().status());assertEquals("LATE-A",result.actions().getFirst().actionNo());
    }

}
