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
    private String profileNo;
    public DefaultOfflinePurchaseFacade(MallRepository orders,FulfillmentRepository operations,OfflineRepository offline,
            SubscriptionAccountRepository accounts,CommerceUnitOfWork uow,StoreDirectoryPort stores,ActorAuthorizationPort auth,
            DefaultMallFacade mall,PaymentFacade payments,RefundFacade refunds,OfflineCollectionPort collection,
            PrivateEvidencePort media,Clock clock,CommerceIdGenerator ids,String profileNo) {
        this(orders,operations,offline,accounts,uow,stores,auth,mall,payments,refunds,collection,media,clock,ids);
        if(profileNo==null || profileNo.isBlank() || profileNo.length()>64)throw new IllegalArgumentException("OFFLINE_PROFILE_REQUIRED");
        this.profileNo=profileNo;
    }
    public QuoteView quote(ActorContext actor,QuoteCommand command) {
        uow.requireOutsideTransaction();
        if(profileNo==null)throw fail("OFFLINE_UNAVAILABLE");
        if(actor==null || command==null || command.store()==null || command.currency()==null)throw fail("INVALID_REQUEST");
        // Pay-domain read happens before any commerce transaction; the quote re-verifies currency and freezes terms.
        var terms=collection.terms(actor.tenantNo(),profileNo,command.currency()).orElseThrow(()->fail("OFFLINE_UNAVAILABLE"));
        return mall.quoteOffline(actor,command,terms);
    }
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
            var live=stores.find(q.store()).filter(s->s.store().equals(q.store()));
            var account=accounts.lockSubscriptionAccount(q.store());
            // Merchants act only on a live store. Operators must still record or fence real money
            // after a store is suspended, so they are bound to the order's frozen store/entity instead.
            if(a.subjectKind()==SubjectKind.ACCOUNT && (live.isEmpty() || live.get().status()!=StoreStatus.ACTIVE
                    || live.get().model()!=CommerceModel.STORE_V1 || !live.get().merchantNo().equals(account.entityNo())))
                throw fail("STORE_UNAVAILABLE");
            String currency=operations.locate(a.tenantNo(),q.orderNo()).filter(l->q.store().equals(l.store()))
                    .orElseThrow(()->fail("NOT_FOUND")).currency();
            orders.lockBalance(q.store(),currency);orders.lockSources(q.store(),clock.millis());orders.lockLots(q.store(),currency,clock.millis());
            var o=orders.order(a.tenantNo(),q.orderNo()).filter(v->v.quote().store().equals(q.store())).orElseThrow(()->fail("NOT_FOUND"));
            if(o.quote().purchase().offline()==null)throw fail("OFFLINE_INTENT_REQUIRED");
            if(a.subjectKind()==SubjectKind.ACCOUNT && !o.quote().entityNo().equals(live.get().merchantNo()))throw fail("FORBIDDEN");
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
            var scope=new PrivateEvidencePort.Scope(order.store(),o.quote().entityNo(),o.orderNo(),actor.subjectKind(),actor.principalNo(),purpose);
            String key=media.newKey(scope,ids.nextBusinessNo("offline-evidence"));
            if(key==null || key.length()>255 || !key.matches("[A-Za-z0-9_\\-/]+\\.(jpg|jpeg|png)"))throw fail("EVIDENCE_UNAVAILABLE");
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
        if(keys==null || keys.isEmpty() || keys.size()>10 || keys.stream().anyMatch(k->k==null || k.isBlank() || k.length()>512)
                || new HashSet<>(keys).size()!=keys.size())throw fail("INVALID_REQUEST");
        var evidence=locked(actor,order,cap,o->keys.stream().map(k->ownEvidence(actor,order,k,purpose)).toList());
        uow.requireOutsideTransaction();
        for(var e:evidence)if(!media.stored(e.scope(),e.assetKey()))throw fail("EVIDENCE_UNAVAILABLE");
        return evidence;
    }
    private static final java.util.Set<String> EVIDENCE_TYPES=java.util.Set.of("image/jpeg","image/png");
    private static final long EVIDENCE_MAX_BYTES=10L*1024*1024;
    public void upload(ActorContext actor,OrderQuery order,String key,PrivateEvidencePort.Upload upload) {
        if(actor==null || order==null || key==null || key.isBlank() || key.length()>255 || upload==null || upload.content()==null
                || upload.size()<1 || upload.size()>EVIDENCE_MAX_BYTES || !EVIDENCE_TYPES.contains(upload.contentType()))
            throw fail("INVALID_REQUEST");
        var record=locked(actor,order,capability(actor,"commerce:mall:offline:evidence"),o->{
            var e=offline.evidence(actor.tenantNo(),key).orElseThrow(()->fail("EVIDENCE_FORBIDDEN"));
            boolean buyerPurpose="BUYER_RECEIPT".equals(e.scope().purpose());
            if(buyerPurpose!=(actor.subjectKind()==SubjectKind.ACCOUNT))throw fail("EVIDENCE_FORBIDDEN");
            return ownEvidence(actor,order,key,e.scope().purpose());
        });
        uow.requireOutsideTransaction();
        if(media.stored(record.scope(),record.assetKey()))throw fail("EVIDENCE_ALREADY_STORED");
        media.store(record.scope(),record.assetKey(),upload);
    }
    public ClaimView submit(ActorContext actor,ClaimCommand c) {
        if(actor==null || actor.subjectKind()!=SubjectKind.ACCOUNT)throw fail("FORBIDDEN");
        if(c==null || c.order()==null || c.declaredAmount()==null || c.declaredAmount().minor()<=0 || c.declaredPaidAt()==null
                || c.evidenceKeys()==null)throw fail("INVALID_REQUEST");
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
        operator(actor);if(c==null || c.order()==null)throw fail("INVALID_REQUEST");text(c.claimNo(),64);text(c.reason(),1024);
        return locked(actor,c.order(),"commerce:mall:offline:review",o->{
            var prior=offline.claim(actor.tenantNo(),o.orderNo(),c.claimNo()).orElseThrow(()->fail("NOT_FOUND"));
            if("DECLINED".equals(prior.status())) {
                if(!actor.principalNo().equals(prior.reviewer()) || !c.reason().equals(prior.reason()))throw fail("IDEMPOTENCY_CONFLICT");
                return prior.view();
            }
            if(!"SUBMITTED".equals(prior.status()))throw fail("CLAIM_ALREADY_REVIEWED");
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
        if(c==null || c.order()==null || c.evidenceKey()==null || c.actualAmount()==null || c.actualAmount().minor()<=0 || c.actualPaidAt()==null
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
            if(c.claimNo()!=null) {
                var claim=offline.claim(actor.tenantNo(),o.orderNo(),c.claimNo()).orElseThrow(()->fail("NOT_FOUND"));
                if(!"SUBMITTED".equals(claim.status()))throw fail("CLAIM_ALREADY_REVIEWED");
                offline.saveClaim(claim.reviewed("REVIEWING",actor.principalNo(),c.reason()));
            }
            var a=new OfflineRepository.Action(c.actionNo(),"CONFIRM",c.order(),actor.principalNo(),clock.millis(),c,null,payment(o),"ACCEPTED",null);
            offline.saveAction(a);return a;
        });
        return advance(action).view();
    }
    public ActionView voidOrder(ActorContext actor,VoidCommand c) {
        operator(actor);if(c==null || c.order()==null || c.evidenceKey()==null || c.unmatchedDispositions()==null)throw fail("INVALID_REQUEST");
        text(c.actionNo(),64);text(c.reconciliationReference(),128);text(c.reason(),1024);
        for(var d:c.unmatchedDispositions().entrySet()){text(d.getKey(),64);text(d.getValue(),1024);}
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
        try {
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
        } catch(IllegalArgumentException refused) {
            // The money authority validated and definitively refused this request (no row written).
            // Persist a terminal outcome; transport/unknown failures stay ACCEPTED for recovery.
            String reason=refused.getMessage()==null || refused.getMessage().isBlank()?"OFFLINE_REJECTED":refused.getMessage();
            var query=new FulfillmentQuery(a.order().store().tenantNo(),a.order().orderNo());
            trusted(query,o->{
                var current=offline.action(query.tenantNo(),query.orderNo(),a.actionNo()).orElseThrow(()->fail("NOT_FOUND"));
                if("ACCEPTED".equals(current.status())) {var r=current.rejected(reason);offline.saveAction(r);settleClaim(r);}
                return null;
            });
            throw fail(reason.length()>64?"OFFLINE_REJECTED":reason);
        }
        var query=new FulfillmentQuery(a.order().store().tenantNo(),a.order().orderNo());
        if(!"UNMATCHED".equals(status))payments.refresh(query);
        var finished=a.completed(status,moneyNo);
        return trusted(query,o->{
            var current=offline.action(query.tenantNo(),query.orderNo(),a.actionNo()).orElseThrow(()->fail("NOT_FOUND"));
            if(!"ACCEPTED".equals(current.status()))return current;
            offline.saveAction(finished);settleClaim(finished);return finished;
        });
    }
    /** Claim follows the operator outcome: confirmed, mismatched, or reopened when the ledger refused. */
    private void settleClaim(OfflineRepository.Action a) {
        if(!"CONFIRM".equals(a.kind()) || a.review().claimNo()==null)return;
        var t=a.order().store().tenantNo();
        offline.claim(t,a.order().orderNo(),a.review().claimNo()).filter(c->"REVIEWING".equals(c.status())).ifPresent(c->
            offline.saveClaim(switch(a.status()) {
                case "APPLIED" -> c.reviewed("CONFIRMED",a.operatorNo(),c.reason());
                case "UNMATCHED" -> c.reviewed("MISMATCHED",a.operatorNo(),c.reason());
                default -> c.reviewed("SUBMITTED",null,null);
            }));
    }

    public void recover(FulfillmentQuery query) {
        uow.requireOutsideTransaction();
        if(query==null)throw fail("INVALID_REQUEST");
        var pending=trusted(query,o->offline.pending(query.tenantNo(),query.orderNo()));
        RuntimeException first=null;
        for(var action:pending) {
            try { advance(action); }
            catch(RuntimeException failure) { if(first==null)first=failure; else first.addSuppressed(failure); }
        }
        if(first!=null)throw first;
    }

}
