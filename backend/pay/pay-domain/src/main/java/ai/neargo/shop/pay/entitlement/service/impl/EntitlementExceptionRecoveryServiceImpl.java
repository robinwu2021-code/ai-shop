package ai.neargo.shop.pay.entitlement.service.impl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionActionWriter;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntGrantMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;
@Service
@Transactional(value="payTxManager",propagation=Propagation.NOT_SUPPORTED)
public class EntitlementExceptionRecoveryServiceImpl implements EntitlementExceptionRecoveryService {
    private final EntitlementExceptionActionWriter writer;
    private final EntitlementExceptionService exceptions;
    private final EntGrantMapper grants;
    private final EntitlementGrantService issuance;
    private final List<EntitlementGrantSourcePort> sources;
    private final ObjectProvider<SourceDecisionPort> decisions;
    private final ObjectMapper json;
    private final ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService delivery;
    public EntitlementExceptionRecoveryServiceImpl(EntitlementExceptionActionWriter writer,
            EntitlementExceptionService exceptions,EntGrantMapper grants,EntitlementGrantService issuance,
            List<EntitlementGrantSourcePort> sources,ObjectProvider<SourceDecisionPort> decisions,ObjectMapper json,
            ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService delivery) {
        this.writer=writer; this.exceptions=exceptions; this.grants=grants; this.issuance=issuance;
        this.sources=sources; this.decisions=decisions; this.json=json; this.delivery=delivery;
    }
    @Override public ExceptionView retryGrant(CatalogScope scope,String no,RecoveryCommand command,String key) {
        return execute(scope,no,command,key,false);
    }
    @Override public ExceptionView querySource(CatalogScope scope,String no,RecoveryCommand command,String key) {
        if("OUTBOX_DELIVERY".equals(exceptions.get(scope,no).kind())) return delivery.queryEvent(scope,no,command,key);
        return execute(scope,no,command,key,true);
    }
    @Override public ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage deadLetters(
            ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope scope,String cursor,int limit) {
        return delivery.deadLetters(scope,cursor,limit);
    }
    @Override public ExceptionView retryEvent(CatalogScope scope,String no,RecoveryCommand command,String key) {
        return delivery.retryEvent(scope,no,command,key);
    }
    private ExceptionView execute(CatalogScope scope,String no,RecoveryCommand command,String key,boolean queryOnly) {
        if(command==null || key==null || key.isBlank() || key.length()>64) throw BizException.of(ErrorCode.BAD_REQUEST);
        var replay=writer.begin(scope,no,command,key,queryOnly?"QUERY_SOURCE":"RETRY_GRANT");
        if(replay!=null) return replay;
        var issue=exceptions.get(scope,no);
        var row=grants.find(scope.tenantNo(),issue.bizNo());
        if(row==null || !scope.entityNo().equals(row.getEntityNo())) throw BizException.of(ErrorCode.CONFLICT);
        var source=json.readValue(row.getSourceSnapshot(),SourceRef.class);
        var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,EntitlementRecords.digest(no+":"+key));
        var owners=sources.stream().filter(p -> p.supports(source)).toList();
        if(owners.size()>1) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var truth=owners.isEmpty()?null:owners.getFirst().decision(ctx,source);
        if(truth==null) {
            var owner=decisions.getIfAvailable();
            if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            truth=owner.query(ctx,source);
        }
        if(truth==null || !source.equals(truth.source())) throw BizException.of(ErrorCode.CONFLICT);
        if(queryOnly) return writer.finish(scope,no,key,false,truth.status().name());
        if(truth.status()==SourceDecisionPort.State.UNKNOWN || truth.status()==SourceDecisionPort.State.PENDING) {
            return writer.finish(scope,no,key,false);
        }
        var result=truth.status()==SourceDecisionPort.State.COMMITTED
                ? issuance.commit(ctx,new EntitlementGrantPort.GrantDecision(row.getGrantNo(),source,truth.decision()))
                : issuance.abort(ctx,new EntitlementGrantPort.AbortGrant(row.getGrantNo(),source,truth.decision()));
        return writer.finish(scope,no,key,result.status()==EntitlementGrantPort.State.COMMITTED
                || result.status()==EntitlementGrantPort.State.ABORTED);
    }
}
