package ai.neargo.shop.paybridge.impl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.CardSaleCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleSourcePort;
import ai.neargo.shop.spi.entitlement.PaidCardSalePlanPort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** Retry original per-unit identities. A partial response never authorizes payment. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class CardSaleCoordinatorImpl implements CardSaleCoordinator {
    private final PaidCardSalePlanPort plans;
    private final List<EntitlementSaleSourcePort> sources;
    private final EntitlementSaleReservationPort sales;
    private final EntitlementGrantPort grants;
    public CardSaleCoordinatorImpl(PaidCardSalePlanPort plans,List<EntitlementSaleSourcePort> sources,
            EntitlementSaleReservationPort sales,EntitlementGrantPort grants) {
        this.plans=plans; this.sources=sources; this.sales=sales; this.grants=grants;
    }
    @Override public Progress recover(EntitlementContext ctx,String orderNo) {
        int held=0,issued=0,released=0,pending=0;
        var units=plans.units(ctx,orderNo);
        for(var unit:units) {
            var c=unit.reservation();
            var owners=sources.stream().filter(p -> p.supports(c.sourceRef())).toList();
            if(owners.size()!=1) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var fact=owners.getFirst().saleSource(ctx,c); var truth=fact.decision();
            if(truth.status()==SourceDecisionPort.State.CANCELLED) {
                var result=sales.release(ctx,new EntitlementSaleReservationPort.ReleaseSale(c,truth.decision()));
                if(result.status()==EntitlementSaleReservationPort.State.RELEASED) released++; else pending++;
            } else if(truth.status()==SourceDecisionPort.State.COMMITTED) {
                if(unit.paidFactRef()==null) throw BizException.of(ErrorCode.CONFLICT);
                var no="CG"+c.sourceRef().grantKey().substring(0,60);
                var command=new EntitlementGrantPort.GrantCommand(no,c.sourceRef(),null,c.templateVersionNo(),
                        c.userNo(),c.entityNo(),1,c.benefitSnapshotHash(),unit.paidFactRef());
                var result=grants.prepare(ctx,command);
                if(result.status()==EntitlementGrantPort.State.PREPARED) {
                    result=grants.commit(ctx,new EntitlementGrantPort.GrantDecision(no,c.sourceRef(),truth.decision()));
                }
                if(result.status()==EntitlementGrantPort.State.COMMITTED) issued++; else pending++;
            } else if(truth.status()==SourceDecisionPort.State.PENDING && fact.canReserve()) {
                var result=sales.reserve(ctx,c);
                if(result.status()==EntitlementSaleReservationPort.State.RESERVED) held++; else pending++;
            } else pending++;
        }
        return new Progress(units.size(),held,issued,released,pending);
    }
}
