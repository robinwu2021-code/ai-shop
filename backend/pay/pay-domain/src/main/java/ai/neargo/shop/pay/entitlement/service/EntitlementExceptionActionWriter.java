package ai.neargo.shop.pay.entitlement.service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.Evidence;
import ai.neargo.shop.pay.entitlement.entity.PayEntException;
import ai.neargo.shop.pay.entitlement.entity.PayEntExceptionAction;
import ai.neargo.shop.pay.mapper.entitlement.PayEntExceptionActionMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntExceptionMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
@Component
public class EntitlementExceptionActionWriter {
    private final PayEntExceptionMapper exceptions;
    private final PayEntExceptionActionMapper actions;
    private final ObjectMapper json;
    public EntitlementExceptionActionWriter(PayEntExceptionMapper exceptions,PayEntExceptionActionMapper actions,ObjectMapper json) {
        this.exceptions=exceptions; this.actions=actions; this.json=json;
    }
    @Transactional("payTxManager")
    public ExceptionView begin(CatalogScope scope,String no,RecoveryCommand c,String key) {
        return begin(scope,no,c,key,"RETRY_GRANT");
    }
    @Transactional("payTxManager")
    public ExceptionView begin(CatalogScope scope,String no,RecoveryCommand c,String key,String mode) {
        if(!java.util.Set.of("RETRY_GRANT","QUERY_SOURCE").contains(mode)) throw BizException.of(ErrorCode.BAD_REQUEST);
        var row=owned(scope,no);
        var payload="RETRY_GRANT".equals(mode) ? java.util.List.of(no,c,scope.entityNo(),scope.actorNo())
                : java.util.List.of(no,c,scope.entityNo(),scope.actorNo(),mode);
        String hash=EntitlementRecords.digest(json.writeValueAsString(payload));
        var previous=actions.lock(scope.tenantNo(),key);
        if(previous!=null) {
            if(!hash.equals(previous.getPayloadHash())) throw BizException.of(ErrorCode.CONFLICT);
            return previous.getResponseSnapshot()==null ? null : json.readValue(previous.getResponseSnapshot(),ExceptionView.class);
        }
        if(row.getVersion()!=c.expectedExceptionRevision() || !"OPEN".equals(row.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var action=new PayEntExceptionAction();
        EntitlementRecords.audit(action,new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,hash));
        action.setActionNo("EA"+EntitlementRecords.digest(scope.tenantNo()+":"+key).substring(0,60));
        action.setOperationKind(mode); action.setExceptionNo(no); action.setRequestKey(key); action.setPayloadHash(hash); action.setReason(c.reason());
        action.setEntityNo(scope.entityNo()); action.setActorNo(scope.actorNo()); actions.claim(action);
        var claimed=actions.lock(scope.tenantNo(),key);
        if(!hash.equals(claimed.getPayloadHash())) throw BizException.of(ErrorCode.CONFLICT);
        row.setStatus("PROCESSING"); row.setLastAction(mode); row.setHandledBy(scope.actorNo());
        row.setHandledAt(EntitlementRecords.now()); row.setUpdatedAt(row.getHandledAt()); row.setUpdatedBy(scope.actorNo());
        EntitlementRecords.changed(exceptions.updateById(row));
        return null;
    }
    @Transactional("payTxManager")
    public ExceptionView finish(CatalogScope scope,String no,String key,boolean resolved) {
        return finish(scope,no,key,resolved,null);
    }
    @Transactional("payTxManager")
    public ExceptionView finish(CatalogScope scope,String no,String key,boolean resolved,String sourceStatus) {
        var row=owned(scope,no); var action=actions.lock(scope.tenantNo(),key);
        if(action==null || !no.equals(action.getExceptionNo()) || !scope.actorNo().equals(action.getActorNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if("DONE".equals(action.getStatus())) return json.readValue(action.getResponseSnapshot(),ExceptionView.class);
        if(!"PROCESSING".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
        if(sourceStatus!=null) {
            if(!"QUERY_SOURCE".equals(action.getOperationKind()) || resolved) throw BizException.of(ErrorCode.CONFLICT);
            var e=json.readValue(row.getEvidence(),Evidence.class);
            row.setEvidence(json.writeValueAsString(new Evidence(e.grantNo(),e.grantStatus(),e.sourceType(),e.reasonCode(),sourceStatus)));
        }
        row.setStatus(resolved?"RESOLVED":"OPEN"); row.setHandledAt(EntitlementRecords.now());
        row.setUpdatedAt(row.getHandledAt()); row.setUpdatedBy(scope.actorNo());
        EntitlementRecords.changed(exceptions.updateById(row));
        var view=new ExceptionView(row.getExceptionNo(),row.getEntityNo(),row.getKind(),row.getBizNo(),row.getStatus(),
                json.readValue(row.getEvidence(),Evidence.class),row.getLastAction(),row.getVersion());
        action.setStatus("DONE"); action.setResponseSnapshot(json.writeValueAsString(view));
        action.setUpdatedAt(EntitlementRecords.now()); action.setUpdatedBy(scope.actorNo());
        EntitlementRecords.changed(actions.updateById(action));
        return view;
    }
    private PayEntException owned(CatalogScope scope,String no) {
        if(!scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var row=exceptions.lock(scope.tenantNo(),no);
        if(row==null || !scope.entityNo().equals(row.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        if(!"GRANT_DELIVERY".equals(row.getKind())) throw BizException.of(ErrorCode.CONFLICT);
        return row;
    }
}
