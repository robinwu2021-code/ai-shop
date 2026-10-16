package ai.neargo.shop.pay.points.recon.impl;

import ai.neargo.shop.pay.points.recon.PointsSourceReconciler;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.spi.points.PointsFactPort;
import ai.neargo.shop.spi.points.SourceFactsPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Partial enqueue is safe: the unchanged cursor causes exact replay on the next scan. */
@Service
public class PointsSourceReconcilerImpl implements PointsSourceReconciler {
    private final ObjectProvider<SourceFactsPort> sources;
    private final PointsFactPort facts;
    private final PointsCursorStore cursors;
    private final ObjectMapper json=new ObjectMapper();
    public PointsSourceReconcilerImpl(ObjectProvider<SourceFactsPort> sources,PointsFactPort facts,
                                     PointsCursorStore cursors) {
        this.sources=sources; this.facts=facts; this.cursors=cursors;
    }
    @Override public Outcome scan(String type,int limit) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"POINTS_SOURCE_REQUIRES_NO_TRANSACTION");
        require(type!=null && !type.isBlank() && limit>0 && limit<=200,"POINTS_BATCH_INVALID");
        String task="POINTS_SOURCE:"+type;
        long after=cursors.current(task);
        var source=sources.getIfAvailable();
        if (source==null) { return new Outcome(false,0,after); }
        var page=source.scan(type,after,limit);
        require(page!=null && page.facts().size()<=limit && page.nextSequence()>=after,"POINTS_SOURCE_PAGE_INVALID");
        long sequence=after;
        for (var fact:page.facts()) {
            require(fact!=null && fact.sequence()>sequence && fact.sequence()<=page.nextSequence(),
                    "POINTS_SOURCE_SEQUENCE_INVALID");
            sequence=fact.sequence();
            int kinds=(fact.lifecycle()==null?0:1)+(fact.closure()==null?0:1)
                    +(fact.zeroCash()==null?0:1)+(fact.refund()==null?0:1);
            require(kinds==1,"POINTS_SOURCE_PAGE_INVALID");
            String factType=fact.lifecycle()!=null?fact.lifecycle().typeCode():fact.closure()!=null?
                    fact.closure().typeCode():fact.zeroCash()!=null?fact.zeroCash().typeCode():fact.refund().typeCode();
            require(type.equals(factType),"POINTS_SOURCE_TYPE_MISMATCH");
            if (fact.earning()!=null) {
                require(fact.lifecycle()!=null && fact.lifecycle().kind()==PointsFactPort.FactKind.PAID
                        && type.equals(fact.earning().typeCode())
                        && fact.lifecycle().subOrderNo().equals(fact.earning().subOrderNo()),
                        "POINTS_SOURCE_EARNING_MISMATCH");
            }
        }
        for (var fact:page.facts()) {
            if (fact.lifecycle()!=null) { facts.accept(fact.lifecycle()); }
            if (fact.earning()!=null) { facts.acceptEarning(fact.earning()); }
            if (fact.closure()!=null) { facts.acceptClosure(fact.closure()); }
            if (fact.zeroCash()!=null) { facts.acceptZeroCash(fact.zeroCash()); }
            if (fact.refund()!=null) { facts.acceptRefundConfirmation(fact.refund()); }
        }
        cursors.advance(task,type,after,page.nextSequence(),json.writeValueAsString(page));
        return new Outcome(true,page.facts().size(),page.nextSequence());
    }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
