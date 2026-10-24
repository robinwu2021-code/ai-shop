package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Number reservations precede money locks. Original recovery does not re-run new-offer eligibility. */
@Service
public class StoredValueBrandRechargeService {
    private final IssuerRepository db;private final StoredValueBrandRechargeWriter writer;
    private final StoredValueBrandRechargeOptions offers;private final SvRechargeMapper recharges;
    private final DocumentNumberGenerator generator;private final DocumentNumberService numbers;private final DocumentNumberCoordinator coordinator;
    public StoredValueBrandRechargeService(IssuerRepository db,StoredValueBrandRechargeWriter writer,StoredValueBrandRechargeOptions offers,SvRechargeMapper recharges,
            @Qualifier("payDocumentNumberGenerator")DocumentNumberGenerator generator,@Qualifier("payDocumentNumbers")DocumentNumberService numbers,
            @Qualifier("payDocumentNumberCoordinator")DocumentNumberCoordinator coordinator){
        this.db=db;this.writer=writer;this.offers=offers;this.recharges=recharges;this.generator=generator;this.numbers=numbers;this.coordinator=coordinator;
    }
    public CursorPage<Option> options(Query q,String cursor,int size){return offers.options(q,cursor,size);}
    public RechargeView confirm(EntitlementContext c,String entity,Create command){
        String original=writer.originalNo(c,entity);
        var reserved=recharges.find(c.tenantNo(),original)==null?generator.reserve(DocumentType.SV_RECHARGE,Instant.now()):null;
        return coordinator.withReservation(Map.of(DocumentType.SV_VOUCHER,1),()->db.write(()->{
            var result=writer.confirm(c,entity,command);
            if(reserved!=null&&recharges.find(c.tenantNo(),result.rechargeNo())!=null)return number(result,numbers.bind(DocumentType.SV_RECHARGE,source(c,result.rechargeNo()),reserved));
            return decorate(c,result);
        }));
    }
    /** Retry an acknowledged original without requiring a fresh customer/offer reference. */
    public RechargeView resume(EntitlementContext c,String entity,Replay replay){
        var prior=writer.replay(c,entity,replay);if(!prior.found())throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        return coordinator.withReservation(Map.of(DocumentType.SV_VOUCHER,1),()->db.write(()->{
            var r=prior.recharge();if(r.status()==ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState.CREDITED||r.status()==ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState.CANCELLED)return decorate(c,r);
            // Synchronous creation/receipt is one transaction. Only received originals may require later recovery.
            if(r.status()!=ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState.RECEIVED)throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT);
            writer.recover(c,entity,r.rechargeNo());return decorate(c,writer.query(c,entity,r.rechargeNo()));
        }));
    }
    public ReplayResult replay(EntitlementContext c,String entity,Replay replay){var r=writer.replay(c,entity,replay);return new ReplayResult(r.found(),r.found()?decorate(c,r.recharge()):null);}
    public RechargeView cancelCreation(EntitlementContext c,String entity,Replay replay){return db.write(()->decorate(c,writer.cancelCreation(c,entity,replay)));}
    public RechargeView query(EntitlementContext c,String entity,String n){
        if(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,n))n=numbers.resolve(c.tenantNo(),DocumentType.SV_RECHARGE,n).filter(s->"PAY".equals(s.sourceSystem())&&"SV_RECHARGE".equals(s.resourceType())).map(DocumentSource::sourceNo).orElseThrow(()->ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND));
        return decorate(c,writer.query(c,entity,n));
    }
    public boolean recover(EntitlementContext c,String entity,String n){return coordinator.withReservation(Map.of(DocumentType.SV_VOUCHER,1),()->db.write(()->writer.recover(c,entity,n)));}
    private DocumentSource source(EntitlementContext c,String n){return new DocumentSource(c.tenantNo(),"PAY","SV_RECHARGE",n);}
    private RechargeView decorate(EntitlementContext c,RechargeView r){return number(r,numbers.findBySource(DocumentType.SV_RECHARGE,source(c,r.rechargeNo())).orElse(null));}
    private static RechargeView number(RechargeView r,String n){return new RechargeView(r.rechargeNo(),r.status(),r.userNo(),r.entityNo(),r.storeNo(),r.currency(),r.paidAmountMinor(),r.creditedTotalMinor(),r.receivedAt(),r.creditedAt(),r.revision(),r.customerNo(),n);}
}
