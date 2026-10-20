package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.numbering.*;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;

/** Adds human numbers without changing frozen facts, receipt identities or the existing credit writer. */
@Service
@Primary
public class StoredValueNumberedPlanRechargeService implements StoredValuePlanRechargeService {
    private final StoredValuePlanRechargeService direct;
    private final SvRechargeMapper recharges;
    private final DocumentNumberGenerator generator;
    private final DocumentNumberService numbers;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final DocumentNumberCoordinator coordinator;
    public StoredValueNumberedPlanRechargeService(
            @Qualifier("storedValuePlanRechargeServiceImpl") StoredValuePlanRechargeService direct,
            SvRechargeMapper recharges,
            @Qualifier("payDocumentNumberGenerator") DocumentNumberGenerator generator,
            @Qualifier("payDocumentNumbers") DocumentNumberService numbers,
            @Qualifier("payTxManager") PlatformTransactionManager manager,ObjectMapper json,
            @Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator coordinator) {
        this.direct=direct;this.recharges=recharges;this.generator=generator;this.numbers=numbers;
        this.transaction=new TransactionTemplate(manager);this.json=json;this.coordinator=coordinator;
    }
    @Override public RechargeView create(EntitlementContext context,String entity,Create command) {
        return numbered(context,entity,()->direct.create(context,entity,command));
    }
    @Override public RechargeView confirmOffline(EntitlementContext context,String entity,Create command) {
        return coordinator.withReservation(Map.of(DocumentType.SV_VOUCHER,1),
            ()->numbered(context,entity,()->direct.confirmOffline(context,entity,command)));
    }
    private RechargeView numbered(EntitlementContext context,String entity,Supplier<RechargeView> action) {
        String internal=no("PR",List.of(context.tenantNo(),entity,context.actorNo(),context.requestId()),json);
        // Existing historical records stay nullable; a read/retry must never allocate a historical mapping.
        var reserved=recharges.find(context.tenantNo(),internal)==null
                ?generator.reserve(DocumentType.SV_RECHARGE,Instant.now()):null;
        return Objects.requireNonNull(transaction.execute(status->{
            RechargeView result=action.get();
            // A cancellation tombstone may have no recharge row; it is not a newly created document.
            if(reserved!=null && recharges.find(context.tenantNo(),result.rechargeNo())!=null) {
                String bound=numbers.bind(DocumentType.SV_RECHARGE,source(context,result.rechargeNo()),reserved);
                return withNumber(result,bound);
            }
            return decorate(context,result);
        }));
    }
    private static DocumentSource source(EntitlementContext context,String rechargeNo) {
        return new DocumentSource(context.tenantNo(),"PAY","SV_RECHARGE",rechargeNo);
    }
    private RechargeView decorate(EntitlementContext context,RechargeView result) {
        if(result==null)return null;
        String number=numbers.findBySource(DocumentType.SV_RECHARGE,source(context,result.rechargeNo())).orElse(null);
        return withNumber(result,number);
    }
    private static RechargeView withNumber(RechargeView result,String number) {
        return new RechargeView(result.rechargeNo(),result.status(),result.userNo(),result.entityNo(),result.storeNo(),
            result.currency(),result.paidAmountMinor(),result.creditedTotalMinor(),result.receivedAt(),result.creditedAt(),
            result.revision(),result.customerNo(),number);
    }
    @Override public RechargeView query(EntitlementContext c,String entity,String rechargeNo) {
        String original=rechargeNo;
        if(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,rechargeNo)) {
            original=numbers.resolve(c.tenantNo(),DocumentType.SV_RECHARGE,rechargeNo)
                .filter(source->"PAY".equals(source.sourceSystem()) && "SV_RECHARGE".equals(source.resourceType()))
                .map(DocumentSource::sourceNo).orElseThrow(()->ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND));
        }
        return decorate(c,direct.query(c,entity,original));
    }
    @Override public ReplayResult replay(EntitlementContext c,String entity,Replay command) {
        var result=direct.replay(c,entity,command);
        return new ReplayResult(result.found(),decorate(c,result.recharge()));
    }
    @Override public RechargeView cancelCreation(EntitlementContext c,String entity,Replay command) {
        return decorate(c,direct.cancelCreation(c,entity,command));
    }
    @Override public void bindCustomer(EntitlementContext c,String customer,String user) { direct.bindCustomer(c,customer,user); }
    @Override public boolean recover(EntitlementContext c,String entity,String rechargeNo) { return direct.recover(c,entity,rechargeNo); }
}
