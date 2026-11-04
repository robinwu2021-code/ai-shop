package ai.neargo.commerce.core.offline;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Durable commercial review workflow; byte and cash authority calls occur between local transactions. */
public final class DefaultOfflinePurchaseFacade implements OfflinePurchaseFacade {
    private final MallRepository orders;
    private final FulfillmentRepository operations;
    private final OfflineRepository offline;
    private final SubscriptionAccountRepository accounts;
    private final CommerceUnitOfWork uow;
    private final StoreDirectoryPort stores;
    private final ActorAuthorizationPort auth;
    private final DefaultMallFacade mall;
    private final PaymentFacade payments;
    private final RefundFacade refunds;
    private final OfflineCollectionPort collection;
    private final PrivateEvidencePort media;
    private final Clock clock;
    private final CommerceIdGenerator ids;
    public DefaultOfflinePurchaseFacade(MallRepository orders,FulfillmentRepository operations,OfflineRepository offline,
            SubscriptionAccountRepository accounts,CommerceUnitOfWork uow,StoreDirectoryPort stores,ActorAuthorizationPort auth,
            DefaultMallFacade mall,PaymentFacade payments,RefundFacade refunds,OfflineCollectionPort collection,
            PrivateEvidencePort media,Clock clock,CommerceIdGenerator ids) {
        this.orders=orders;this.operations=operations;this.offline=offline;this.accounts=accounts;this.uow=uow;
        this.stores=stores;this.auth=auth;this.mall=mall;this.payments=payments;this.refunds=refunds;this.collection=collection;
        this.media=media;this.clock=clock;this.ids=ids;
    }
    private static MallException fail(String code) { return new MallException(code); }
    private static void text(String v,int max) { if(v==null || v.isBlank() || v.length()>max)throw fail("INVALID_REQUEST"); }
    private <T>T locked(ActorContext a,OrderQuery q,String capability,Function<MallSnapshot.Order,T> work) {
        if(a==null || q==null || q.store()==null || !q.store().tenantNo().equals(a.tenantNo())
                || a.principalNo()==null || a.accountSessionFingerprint()==null || a.accountSessionFingerprint().isBlank()
                || a.subjectKind()!=SubjectKind.ACCOUNT && a.subjectKind()!=SubjectKind.OPERATOR)throw fail("FORBIDDEN");
        text(q.orderNo(),64);
        return uow.required(()->{
            var allowed=auth.authorize(a,q.store(),capability);
            if(allowed==null || !allowed.allowed())throw fail("FORBIDDEN");
            var live=stores.find(q.store()).filter(s->s.store().equals(q.store())).orElseThrow(()->fail("NOT_FOUND"));
            var account=accounts.lockSubscriptionAccount(q.store());
            if(live.status()!=StoreStatus.ACTIVE || live.model()!=CommerceModel.STORE_V1
                    || !live.merchantNo().equals(account.entityNo()))throw fail("STORE_UNAVAILABLE");
            String currency=operations.locate(a.tenantNo(),q.orderNo()).filter(l->q.store().equals(l.store()))
                    .orElseThrow(()->fail("NOT_FOUND")).currency();
            orders.lockBalance(q.store(),currency);orders.lockSources(q.store(),clock.millis());orders.lockLots(q.store(),currency,clock.millis());
            var o=orders.order(a.tenantNo(),q.orderNo()).filter(v->v.quote().store().equals(q.store())).orElseThrow(()->fail("NOT_FOUND"));
            if(o.quote().purchase().offline()==null)throw fail("OFFLINE_INTENT_REQUIRED");
            if(!o.quote().entityNo().equals(live.merchantNo()))throw fail("FORBIDDEN");
            if(a.subjectKind()==SubjectKind.ACCOUNT && "ACCOUNT".equals(o.quote().purchase().buyerKind())
                    && !o.quote().buyerNo().equals(a.principalNo()))throw fail("FORBIDDEN");
            return work.apply(o);
        });
    }
    private String capability(ActorContext actor,String ops) {
        return actor!=null && actor.subjectKind()==SubjectKind.OPERATOR?ops:"commerce:mall:purchase";
    }
    private static void operator(ActorContext a) { if(a==null || a.subjectKind()!=SubjectKind.OPERATOR)throw fail("FORBIDDEN"); }
    public EvidenceTicket reserveEvidence(ActorContext actor,OrderQuery order,String purpose) {
        boolean buyer="BUYER_RECEIPT".equals(purpose);
        if(!buyer && !Set.of("PLATFORM_RECEIPT","RECONCILIATION","REFUND_PROOF").contains(purpose))throw fail("INVALID_REQUEST");
        if(buyer && (actor==null || actor.subjectKind()!=SubjectKind.ACCOUNT) || !buyer && (actor==null || actor.subjectKind()!=SubjectKind.OPERATOR))throw fail("FORBIDDEN");
        return locked(actor,order,capability(actor,"commerce:mall:offline:evidence"),o->{
            String key="mall-private/"+ids.nextBusinessNo("offline-evidence")+".jpg";
            var scope=new PrivateEvidencePort.Scope(order.store(),o.quote().entityNo(),o.orderNo(),actor.subjectKind(),actor.principalNo(),purpose);
            offline.reserve(new OfflineRepository.Evidence(key,scope,clock.millis()));
            return new EvidenceTicket(key,purpose);
        });
    }
    private OfflineRepository.Evidence ownEvidence(ActorContext a,OrderQuery q,String key,String purpose) {
        var e=offline.evidence(a.tenantNo(),key).orElseThrow(()->fail("EVIDENCE_FORBIDDEN"));
        var s=e.scope();
        if(!s.store().equals(q.store()) || !s.orderNo().equals(q.orderNo()) || s.submitterKind()!=a.subjectKind()
                || !s.submitterNo().equals(a.principalNo()) || !s.purpose().equals(purpose))throw fail("EVIDENCE_FORBIDDEN");
        return e;
    }
    private List<OfflineRepository.Evidence> verifyEvidence(ActorContext actor,OrderQuery order,List<String> keys,String purpose,String cap) {
        if(keys==null || keys.isEmpty() || keys.size()>10 || new HashSet<>(keys).size()!=keys.size())throw fail("INVALID_REQUEST");
        var evidence=locked(actor,order,cap,o->keys.stream().map(k->ownEvidence(actor,order,k,purpose)).toList());
        uow.requireOutsideTransaction();
        for(var e:evidence)if(!media.stored(e.scope(),e.assetKey()))throw fail("EVIDENCE_UNAVAILABLE");
        return evidence;
    }
    public ClaimView submit(ActorContext actor,ClaimCommand c) {
        if(actor==null || actor.subjectKind()!=SubjectKind.ACCOUNT)throw fail("FORBIDDEN");
        if(c==null || c.declaredAmount()==null || c.declaredAmount().minor()<=0 || c.declaredPaidAt()==null)throw fail("INVALID_REQUEST");
        text(c.claimNo(),64);text(c.bankReference(),128);
        verifyEvidence(actor,c.order(),c.evidenceKeys(),"BUYER_RECEIPT","commerce:mall:purchase");
        return locked(actor,c.order(),"commerce:mall:purchase",o->{
            var prior=offline.claim(actor.tenantNo(),o.orderNo(),c.claimNo());
            if(prior.isPresent()) {
                if(!prior.get().command().equals(c) || !prior.get().submitterNo().equals(actor.principalNo()))throw fail("IDEMPOTENCY_CONFLICT");
                return prior.get().view();
            }
            var claim=new OfflineRepository.Claim(c,actor.principalNo(),clock.millis(),"SUBMITTED",null,null);
            offline.saveClaim(claim);return claim.view();
        });
    }
    public ClaimView decline(ActorContext actor,DeclineCommand c) {
        operator(actor);if(c==null)throw fail("INVALID_REQUEST");text(c.reason(),1024);
        return locked(actor,c.order(),"commerce:mall:offline:review",o->{
            var prior=offline.claim(actor.tenantNo(),o.orderNo(),c.claimNo()).orElseThrow(()->fail("NOT_FOUND"));
            if("DECLINED".equals(prior.status())) {
                if(!actor.principalNo().equals(prior.reviewer()) || !c.reason().equals(prior.reason()))throw fail("IDEMPOTENCY_CONFLICT");
                return prior.view();
            }
            var declined=new OfflineRepository.Claim(prior.command(),prior.submitterNo(),prior.submittedAt(),"DECLINED",actor.principalNo(),c.reason());
            offline.saveClaim(declined);return declined.view();
        });
    }
    public String evidence(ActorContext actor,OrderQuery query,String key) {
        var record=locked(actor,query,capability(actor,"commerce:mall:offline:evidence"),o->{
            var e=offline.evidence(actor.tenantNo(),key).orElseThrow(()->fail("NOT_FOUND"));
            if(!e.scope().orderNo().equals(query.orderNo()) || !e.scope().store().equals(query.store()))throw fail("EVIDENCE_FORBIDDEN");
            if(actor.subjectKind()==SubjectKind.ACCOUNT && (!"BUYER_RECEIPT".equals(e.scope().purpose())
                    || !actor.principalNo().equals(e.scope().submitterNo()) || e.scope().submitterKind()!=SubjectKind.ACCOUNT))throw fail("EVIDENCE_FORBIDDEN");
            return e;
        });
        uow.requireOutsideTransaction();return media.signedRead(record.scope(),record.assetKey());
    }
    public List<String> retainedEvidenceKeys(String tenant) { return uow.required(()->offline.retained(tenant)); }
    private PaymentIntentCommand payment(MallSnapshot.Order order) {
        return operations.operations(order.quote().store().tenantNo(),order.orderNo()).stream()
                .filter(op->"PAYMENT".equals(op.kind())).map(ai.neargo.commerce.core.fulfillment.FulfillmentRepository.Operation::payment).findFirst()
                .filter(p->p.offline()!=null).orElseThrow(()->fail("PAYMENT_INTENT_REQUIRED"));
    }
    private <T>T trusted(FulfillmentQuery q,Function<MallSnapshot.Order,T> work) {
        return uow.required(()->{
            var location=operations.locate(q.tenantNo(),q.orderNo()).orElseThrow(()->fail("NOT_FOUND"));
            accounts.lockSubscriptionAccount(location.store());
            orders.lockBalance(location.store(),location.currency());orders.lockSources(location.store(),clock.millis());
            orders.lockLots(location.store(),location.currency(),clock.millis());
            return work.apply(orders.order(q.tenantNo(),q.orderNo()).orElseThrow(()->fail("NOT_FOUND")));
        });
    }
    public ActionView confirm(ActorContext actor,ReviewCommand c) {
        operator(actor);
        if(c==null || c.actualAmount()==null || c.actualAmount().minor()<=0 || c.actualPaidAt()==null
                || c.actualPaidAt().getNano()!=0 || c.actualPaidAt().isAfter(clock.instant()))throw fail("INVALID_REQUEST");
        text(c.actionNo(),64);text(c.actualReceiver(),64);text(c.bankReference(),128);text(c.reason(),1024);
        verifyEvidence(actor,c.order(),List.of(c.evidenceKey()),"PLATFORM_RECEIPT","commerce:mall:offline:confirm");
        var action=locked(actor,c.order(),"commerce:mall:offline:confirm",o->{
            var previous=offline.action(actor.tenantNo(),o.orderNo(),c.actionNo());
            if(previous.isPresent()) {
                var p=previous.get();
                if(!c.equals(p.review()) || !actor.principalNo().equals(p.operatorNo()))throw fail("IDEMPOTENCY_CONFLICT");
                return p;
            }
            if(c.claimNo()!=null)offline.claim(actor.tenantNo(),o.orderNo(),c.claimNo()).orElseThrow(()->fail("NOT_FOUND"));
            var a=new OfflineRepository.Action(c.actionNo(),"CONFIRM",c.order(),actor.principalNo(),clock.millis(),c,null,payment(o),"ACCEPTED",null);
            offline.saveAction(a);return a;
        });
        return advance(action).view();
    }
    public ActionView voidOrder(ActorContext actor,VoidCommand c) {
        operator(actor);if(c==null)throw fail("INVALID_REQUEST");
        text(c.actionNo(),64);text(c.reconciliationReference(),128);text(c.reason(),1024);
        verifyEvidence(actor,c.order(),List.of(c.evidenceKey()),"RECONCILIATION","commerce:mall:offline:void");
        var action=locked(actor,c.order(),"commerce:mall:offline:void",o->{
            var previous=offline.action(actor.tenantNo(),o.orderNo(),c.actionNo());
            if(previous.isPresent()) {
                var p=previous.get();
                if(!c.equals(p.reconciliation()) || !actor.principalNo().equals(p.operatorNo()))throw fail("IDEMPOTENCY_CONFLICT");
                return p;
            }
            var a=new OfflineRepository.Action(c.actionNo(),"VOID",c.order(),actor.principalNo(),clock.millis(),null,c,payment(o),"ACCEPTED",null);
            offline.saveAction(a);return a;
        });
        return advance(action).view();
    }
    private OfflineRepository.Action advance(OfflineRepository.Action a) {
        uow.requireOutsideTransaction();
        if(!"ACCEPTED".equals(a.status()))return a;
        String status="APPLIED",moneyNo=null;
        if("CONFIRM".equals(a.kind())) {
            var c=a.review();
            var observed=collection.confirm(new OfflineCollectionPort.ManualPayment(a.payment(),a.operatorNo(),c.actualAmount(),
                    c.actualPaidAt(),c.actualReceiver(),c.bankReference(),c.evidenceKey(),c.historical(),java.time.Instant.ofEpochMilli(a.acceptedAt())));
            if(observed==null || !Set.of("MATCHED","LATE","UNMATCHED").contains(observed.disposition()) || observed.paymentNo()==null)
                throw fail("OFFLINE_MONEY_UNKNOWN");
            moneyNo=observed.paymentNo();
            if("UNMATCHED".equals(observed.disposition()))status="UNMATCHED";
        } else if("VOID".equals(a.kind())) {
            var c=a.reconciliation();
            collection.reconcile(new OfflineCollectionPort.Reconciliation(a.payment(),a.operatorNo(),c.reconciliationReference(),c.reason(),
                    c.evidenceKey(),java.time.Instant.ofEpochMilli(a.acceptedAt()),c.unmatchedDispositions()));
        } else throw fail("INVALID_STATE");
        var query=new FulfillmentQuery(a.order().store().tenantNo(),a.order().orderNo());
        if(!"UNMATCHED".equals(status))payments.refresh(query);
        var finished=a.completed(status,moneyNo);
        return trusted(query,o->{
            var current=offline.action(query.tenantNo(),query.orderNo(),a.actionNo()).orElseThrow(()->fail("NOT_FOUND"));
            if(!"ACCEPTED".equals(current.status()))return current;
            offline.saveAction(finished);return finished;
        });
    }
    public void recover(FulfillmentQuery query) {
        uow.requireOutsideTransaction();
        if(query==null)throw fail("INVALID_REQUEST");
        var pending=trusted(query,o->offline.pending(query.tenantNo(),query.orderNo()));
        for(var action:pending)advance(action);
    }

}
