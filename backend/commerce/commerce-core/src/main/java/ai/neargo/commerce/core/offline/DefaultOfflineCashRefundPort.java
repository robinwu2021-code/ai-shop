package ai.neargo.commerce.core.offline;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.port.OfflineCashRefundPort.*;
import ai.neargo.commerce.core.offline.CashRefundRepository.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import java.time.*;
import java.util.*;

/** Full remainder refunds of real, non-delivered cash. No bank transfers are executed. */
public final class DefaultOfflineCashRefundPort implements OfflineCashRefundPort {
    private final CashRefundRepository repo;private final CommerceUnitOfWork uow;
    private final OfflineCollectionPort cash;private final PlatformPaymentPort payments;private final Clock clock;
    public DefaultOfflineCashRefundPort(CashRefundRepository repo,CommerceUnitOfWork uow,OfflineCollectionPort cash,PlatformPaymentPort payments,Clock clock){this.repo=repo;this.uow=uow;this.cash=cash;this.payments=payments;this.clock=clock;}
    private static MallException fail(String code){return new MallException(code);}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max)throw fail("INVALID_REQUEST");}
    private static void target(Target t){if(t==null||t.store()==null)throw fail("INVALID_REQUEST");text(t.store().tenantNo(),64);text(t.store().storeNo(),64);text(t.orderNo(),64);}
    private Refund load(Target t,String no,boolean lock){target(t);var r=repo.find(t.store().tenantNo(),no,lock).orElseThrow(()->fail("NOT_FOUND"));if(!r.target().store().equals(t.store())||!r.target().orderNo().equals(t.orderNo()))throw fail("FORBIDDEN");return r;}
    public List<View> list(Target t){target(t);return uow.required(()->repo.list(t.store().tenantNo(),t.orderNo()).stream().filter(r->r.target().store().equals(t.store())).map(Refund::view).toList());}
    public View request(Target t,String operator,Request c){
        target(t);text(operator,64);if(c==null)throw fail("INVALID_REQUEST");text(c.reason(),500);text(c.paymentNo(),64);
        if(c.refundNo()==null||!c.refundNo().matches("cash-[A-Za-z0-9_-]{1,59}"))throw fail("INVALID_REQUEST");
        var old=uow.required(()->repo.find(t.store().tenantNo(),c.refundNo(),false));
        if(old.isPresent()){var r=uow.required(()->load(t,c.refundNo(),false));if(!r.command().equals(c)||!r.reviewer().equals(operator))throw fail("IDEMPOTENCY_CONFLICT");return recover(t,c.refundNo());}
        uow.requireOutsideTransaction();
        var actual=cash.receipts(t.store().tenantNo(),t.orderNo()).stream().filter(r->r.paymentNo().equals(c.paymentNo())).findFirst().orElseThrow(()->fail("REFUND_ORIGINAL_UNPROVEN"));
        if(!actual.exceptionCash()&&!t.allowUnfulfilledMatchedCash())throw fail("REFUND_REQUIRES_ENTITLEMENT_REVIEW");
        long remaining=Math.subtractExact(actual.amount().minor(),actual.refundedMinor());if(remaining<=0||actual.pendingRefund())throw fail("REFUND_CAPACITY_UNAVAILABLE");
        var intent=new PaymentRefundCommand(t.store().tenantNo(),t.orderNo(),c.refundNo(),new Money(actual.amount().currency(),remaining),c.reason(),t.store(),actual.paymentAttemptNo(),actual.paymentNo(),actual.platformMerchantNo(),actual.amount());
        uow.required(()->{var prior=repo.find(t.store().tenantNo(),c.refundNo(),true);if(prior.isPresent()){if(!prior.get().command().equals(c)||!prior.get().target().store().equals(t.store())||!prior.get().target().orderNo().equals(t.orderNo())||!prior.get().reviewer().equals(operator))throw fail("IDEMPOTENCY_CONFLICT");}else repo.save(new Refund(t,c,operator,clock.millis(),intent,"PREPARED",null,List.of()));return null;});
        return recover(t,c.refundNo());
    }
    public View adopt(Target t,String operator,PaymentRefundCommand p){
        target(t);text(operator,64);
        if(p==null||p.refundNo()==null||!p.refundNo().matches("late-[A-Za-z0-9_-]{1,59}")||!t.store().equals(p.store())||!t.store().tenantNo().equals(p.tenantNo())||!t.orderNo().equals(p.orderNo()))throw fail("REFUND_ORIGINAL_UNPROVEN");
        var existing=uow.required(()->repo.find(p.tenantNo(),p.refundNo(),false));
        if(existing.isEmpty()){
            uow.requireOutsideTransaction();var actual=cash.receipts(p.tenantNo(),p.orderNo()).stream().filter(x->x.paymentNo().equals(p.originalPaymentNo())).findFirst().orElseThrow(()->fail("REFUND_ORIGINAL_UNPROVEN"));
            if(!actual.closed()||!actual.amount().equals(p.originalPaid())||!actual.paymentAttemptNo().equals(p.paymentAttemptNo())||!actual.platformMerchantNo().equals(p.platformMerchantNo())||!p.amount().currency().equals(actual.amount().currency())||p.amount().minor()<=0||p.amount().minor()>actual.amount().minor())throw fail("REFUND_ORIGINAL_UNPROVEN");
            uow.required(()->{if(repo.find(p.tenantNo(),p.refundNo(),true).isEmpty())repo.save(new Refund(t,new Request(p.refundNo(),p.originalPaymentNo(),p.reason()),operator,clock.millis(),p,"PREPARED",null,List.of()));return null;});
        }
        var saved=uow.required(()->load(t,p.refundNo(),false));if(!saved.payment().equals(p))throw fail("IDEMPOTENCY_CONFLICT");return recover(t,p.refundNo());
    }
    public View payout(Target t,String operator,String no,Payout c){
        text(operator,64);if(c==null||c.actualAmount()==null||c.paidOutAt()==null||c.paidOutAt().getNano()!=0||c.paidOutAt().isAfter(clock.instant()))throw fail("INVALID_REQUEST");text(c.actionNo(),64);text(c.payeeReference(),128);text(c.bankReference(),128);text(c.evidenceKey(),512);
        uow.required(()->{var r=load(t,no,true);var prior=r.attempts().stream().filter(a->a.command().actionNo().equals(c.actionNo())).findFirst();if(prior.isPresent()){if(!prior.get().command().equals(c)||!prior.get().operatorNo().equals(operator))throw fail("IDEMPOTENCY_CONFLICT");return null;}if(!"AWAITING_PAYOUT".equals(r.status())||!r.payment().amount().equals(c.actualAmount()))throw fail("INVALID_STATE");var actions=new ArrayList<>(r.attempts());actions.add(new Attempt(operator,c,clock.millis(),"ACCEPTED",null));repo.save(r.state("PAYOUT_ACCEPTED",null,actions));return null;});
        return recover(t,no);
    }
    private boolean verified(Refund r,PaymentRefundOutcome outcome){
        if(outcome==null||outcome.status()!=AsyncStatus.SUCCEEDED)return false;var f=outcome.verifiedFact();var p=r.payment();
        if(f==null||f.status()!=AsyncStatus.SUCCEEDED||!f.definitiveFinal()||f.observedAt()==null||f.verificationReference()==null||f.verificationReference().isBlank()||!Objects.equals(f.tenantNo(),p.tenantNo())||!Objects.equals(f.orderNo(),p.orderNo())||!Objects.equals(f.refundNo(),p.refundNo())||!Objects.equals(outcome.refundNo(),p.refundNo())||!Objects.equals(f.store(),p.store())||!Objects.equals(f.paymentAttemptNo(),p.paymentAttemptNo())||!Objects.equals(f.originalPaymentNo(),p.originalPaymentNo())||!Objects.equals(f.platformMerchantNo(),p.platformMerchantNo())||!Objects.equals(f.originalPaid(),p.originalPaid())||!Objects.equals(f.refunded(),p.amount()))throw fail("REFUND_FACT_MISMATCH");return true;
    }
    public View recover(Target t,String no){
        var r=uow.required(()->load(t,no,false));uow.requireOutsideTransaction();
        if("PREPARED".equals(r.status())){PaymentRefundOutcome outcome;try{outcome=payments.refund(r.payment());}catch(IllegalArgumentException refused){return rejected(t,no,null,refused).view();}boolean done=verified(r,outcome);if(done||outcome!=null&&outcome.status()==AsyncStatus.PENDING)uow.required(()->{var live=load(t,no,true);if("PREPARED".equals(live.status()))repo.save(live.state(done?"REFUNDED":"AWAITING_PAYOUT",null,live.attempts()));return null;});
        }else if("PAYOUT_ACCEPTED".equals(r.status())){var a=r.attempts().stream().filter(x->"ACCEPTED".equals(x.status())).findFirst().orElseThrow(()->fail("INVALID_STATE"));var p=a.command();try{cash.payout(new OfflineCollectionPort.RefundPayout(t.store().tenantNo(),t.orderNo(),no,a.operatorNo(),p.actualAmount(),p.paidOutAt(),p.payeeReference(),p.bankReference(),p.evidenceKey(),Instant.ofEpochMilli(a.acceptedAt())));}catch(IllegalArgumentException refused){return rejected(t,no,p.actionNo(),refused).view();}var result=payments.queryRefund(new PaymentRefundQuery(t.store().tenantNo(),t.orderNo(),no));if(verified(r,result))uow.required(()->{var live=load(t,no,true);if("PAYOUT_ACCEPTED".equals(live.status()))repo.save(live.state("REFUNDED",null,live.attempts().stream().map(x->x.command().actionNo().equals(p.actionNo())?new Attempt(x.operatorNo(),x.command(),x.acceptedAt(),"APPLIED",null):x).toList()));return null;});}
        return uow.required(()->load(t,no,false).view());
    }
    private Refund rejected(Target t,String no,String action,IllegalArgumentException failure){String code=failure.getMessage()!=null&&failure.getMessage().matches("[A-Z0-9_]{1,64}")?failure.getMessage():"OFFLINE_REFUND_REJECTED";
        return uow.required(()->{var live=load(t,no,true);if(action==null&&"PREPARED".equals(live.status())){live=live.state("REJECTED",code,live.attempts());repo.save(live);}else if(action!=null&&"PAYOUT_ACCEPTED".equals(live.status())){live=live.state("AWAITING_PAYOUT",code,live.attempts().stream().map(x->x.command().actionNo().equals(action)?new Attempt(x.operatorNo(),x.command(),x.acceptedAt(),"REJECTED",code):x).toList());repo.save(live);}return live;});}
    public Sweep recoverPending(String tenant,String after,int limit){if(limit<1||limit>200)throw fail("INVALID_REQUEST");var ids=uow.required(()->repo.pending(tenant,after,limit));int failed=0;for(String no:ids)try{var r=uow.required(()->repo.find(tenant,no,false).orElseThrow());recover(r.target(),no);}catch(RuntimeException retry){failed++;}return new Sweep(ids.size(),failed,ids.isEmpty()?after:ids.getLast());}
    public Optional<Boolean> recoverHandoff(String tenant,String order,String no){var r=uow.required(()->repo.find(tenant,no,false));if(r.isEmpty()||!r.get().target().orderNo().equals(order))return Optional.empty();var state=recover(r.get().target(),no);if(no.startsWith("late-"))return Optional.empty();return Optional.of("REFUNDED".equals(state.status()));}
}
