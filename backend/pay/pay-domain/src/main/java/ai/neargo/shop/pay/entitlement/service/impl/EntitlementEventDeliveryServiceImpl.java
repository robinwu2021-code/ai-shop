package ai.neargo.shop.pay.entitlement.service.impl;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.entitlement.service.EntitlementCursorCodec;
import ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetter;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeliveryEvidence;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.Evidence;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.pay.entitlement.entity.PayEntException;
import ai.neargo.shop.pay.entitlement.entity.PayEntExceptionAction;
import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxCodec;
import ai.neargo.shop.pay.mapper.entitlement.PayEntExceptionActionMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntExceptionMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Message-only recovery. No dependencies on issuance, use, account or remote bridge writers. */
@Service("entitlementEventDeliveryService")
@Transactional("payTxManager")
public class EntitlementEventDeliveryServiceImpl implements EntitlementEventDeliveryService {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    private static final String TENANT="MAIN";
    private final PayEntOutboxMapper outbox;
    private final PayEntExceptionMapper exceptions;
    private final PayEntExceptionActionMapper actions;
    private final ObjectMapper json;
    private final EntitlementCursorCodec cursors;
    public EntitlementEventDeliveryServiceImpl(PayEntOutboxMapper outbox,PayEntExceptionMapper exceptions,
            PayEntExceptionActionMapper actions,ObjectMapper json,EntitlementCursorCodec cursors) {
        this.outbox=outbox; this.exceptions=exceptions; this.actions=actions; this.json=json; this.cursors=cursors;
    }
    @Transactional(value="payTxManager",readOnly=true)
    public DeadLetterPage deadLetters(ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope scope,String cursor,int limit) {
        if(!TENANT.equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(scope.entityNos()!=null) throw BizException.of(ErrorCode.FORBIDDEN);
        if(limit<1 || limit>100 || (cursor!=null && (cursor.isBlank() || cursor.length()>512)))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        String filters="OUTBOX_DEAD_ASC_V1";
        long afterId=cursor==null?0:cursors.decode(cursor,TENANT,scope.actorNo(),filters);
        var rows=outbox.selectDeadMetadata(TENANT,afterId,limit+1);
        var page=rows.stream().limit(limit).map(e -> new DeadLetter(e.getEventNo(),e.getEventType(),e.getSchemaVersion(),
                e.getAggregateNo(),e.getAttempts(),e.getOccurredAt(),e.getUpdatedAt())).toList();
        return new DeadLetterPage(page,rows.size()>limit
                ?cursors.encode(TENANT,scope.actorNo(),filters,rows.get(limit-1).getId()):null);
    }
    public ExceptionView captureDead(String eventNo) {
        var event=outbox.findByEventNo(TENANT,eventNo);
        if(event==null || !"DEAD".equals(event.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
        var fact=PayEntitlementOutboxCodec.decode(event,json);
        var row=new PayEntException();
        EntitlementRecords.audit(row,new EntitlementContext(TENANT,"SYSTEM",eventNo,event.getPayloadHash()));
        row.setExceptionNo("EX"+EntitlementRecords.digest(TENANT+":OUTBOX_DELIVERY:"+eventNo).substring(0,60));
        row.setEntityNo(fact.entityNo()); row.setKind("OUTBOX_DELIVERY"); row.setBizNo(eventNo);
        row.setEvidence(json.writeValueAsString(evidence(event))); int created = exceptions.claim(row);
        if (created == 1) documents.bind(DocumentType.ENTITLEMENT_EXCEPTION, TENANT, row.getExceptionNo());
        row=exceptions.lock(TENANT,row.getExceptionNo());
        if(!fact.entityNo().equals(row.getEntityNo())) throw BizException.of(ErrorCode.CONFLICT);
        if("RESOLVED".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
        String snapshot=json.writeValueAsString(evidence(event));
        if(!"OPEN".equals(row.getStatus()) || !snapshot.equals(row.getEvidence())) {
            row.setStatus("OPEN"); row.setEvidence(snapshot); changed(row,"SYSTEM");
        }
        // Intentionally no ExceptionOpened event: a failed notification must not recursively produce more events.
        return view(row);
    }
    public void resolveSent(String eventNo) {
        var event=outbox.findByEventNo(TENANT,eventNo);
        if(event==null || !"SENT".equals(event.getStatus())) return;
        var existing=exceptions.findDelivery(TENANT,eventNo);
        if(existing==null) return;
        var fact=PayEntitlementOutboxCodec.decode(event,json);
        var row=exceptions.lock(TENANT,existing.getExceptionNo());
        if(!fact.entityNo().equals(row.getEntityNo())) throw BizException.of(ErrorCode.CONFLICT);
        if(!"RESOLVED".equals(row.getStatus())) {
            row.setStatus("RESOLVED"); row.setEvidence(json.writeValueAsString(evidence(event)));
            row.setHandledAt(EntitlementRecords.now()); changed(row,"SYSTEM");
        }
    }
    public ExceptionView retryEvent(CatalogScope scope,String no,RecoveryCommand command,String key) {
        return operate(scope,no,command,key,true);
    }
    public ExceptionView queryEvent(CatalogScope scope,String no,RecoveryCommand command,String key) {
        return operate(scope,no,command,key,false);
    }
    private ExceptionView operate(CatalogScope scope,String no,RecoveryCommand command,String key,boolean retry) {
        if(!scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!TENANT.equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(command==null || key==null || key.isBlank() || key.length()>64) throw BizException.of(ErrorCode.BAD_REQUEST);
        // Read identity first; all writers then lock event -> exception -> action in that order.
        var existing=exceptions.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<PayEntException>lambdaQuery()
                .eq(PayEntException::getTenantNo,TENANT).eq(PayEntException::getExceptionNo,no)
                .eq(PayEntException::getEntityNo,scope.entityNo()));
        if(existing==null) throw BizException.of(ErrorCode.NOT_FOUND);
        if(!"OUTBOX_DELIVERY".equals(existing.getKind())) throw BizException.of(ErrorCode.CONFLICT);
        var event=outbox.findByEventNo(TENANT,existing.getBizNo());
        if(event==null || !scope.entityNo().equals(PayEntitlementOutboxCodec.decode(event,json).entityNo()))
            throw BizException.of(ErrorCode.CONFLICT);
        var row=exceptions.lock(TENANT,no);
        String mode=retry?"RETRY_EVENT":"QUERY_EVENT";
        String hash=EntitlementRecords.digest(json.writeValueAsString(List.of(no,command,scope.entityNo(),scope.actorNo(),mode)));
        var previous=actions.lock(TENANT,key);
        if(previous!=null) {
            if(!hash.equals(previous.getPayloadHash()) || !"DONE".equals(previous.getStatus()))
                throw BizException.of(ErrorCode.CONFLICT);
            return json.readValue(previous.getResponseSnapshot(),ExceptionView.class);
        }
        if(row.getVersion()!=command.expectedExceptionRevision() || "RESOLVED".equals(row.getStatus()))
            throw BizException.of(ErrorCode.CONFLICT);
        if(retry) {
            if(!"OPEN".equals(row.getStatus()) || !"DEAD".equals(event.getStatus()))
                throw BizException.of(ErrorCode.CONFLICT);
            EntitlementRecords.changed(outbox.requeue(event.getId(),TENANT,event.getVersion(),EntitlementRecords.now(),scope.actorNo()));
            event.setStatus("PENDING"); event.setAttempts(0); row.setStatus("PROCESSING");
        } else if("SENT".equals(event.getStatus())) row.setStatus("RESOLVED");
        else if("DEAD".equals(event.getStatus())) row.setStatus("OPEN");
        var action=new PayEntExceptionAction();
        EntitlementRecords.audit(action,new EntitlementContext(TENANT,scope.actorNo(),key,hash));
        action.setActionNo("EA"+EntitlementRecords.digest(TENANT+":"+key).substring(0,60));
        action.setExceptionNo(no); action.setEntityNo(scope.entityNo()); action.setActorNo(scope.actorNo());
        action.setOperationKind(mode); action.setRequestKey(key); action.setPayloadHash(hash); action.setReason(command.reason());
        int created = actions.claim(action);
        action=actions.lock(TENANT,key);
        if(!hash.equals(action.getPayloadHash())) throw BizException.of(ErrorCode.CONFLICT);
        if (created == 1) documents.bind(DocumentType.ENTITLEMENT_EXCEPTION_ACTION, TENANT, action.getActionNo());
        row.setEvidence(json.writeValueAsString(evidence(event))); row.setLastAction(mode);
        row.setHandledBy(scope.actorNo()); row.setHandledAt(EntitlementRecords.now()); changed(row,scope.actorNo());
        var result=view(row);
        action.setStatus("DONE"); action.setResponseSnapshot(json.writeValueAsString(result));
        action.setUpdatedAt(EntitlementRecords.now()); action.setUpdatedBy(scope.actorNo());
        EntitlementRecords.changed(actions.updateById(action));
        return result;
    }
    private void changed(PayEntException row,String actor) {
        row.setUpdatedAt(EntitlementRecords.now()); row.setUpdatedBy(actor);
        EntitlementRecords.changed(exceptions.updateById(row));
    }
    private Evidence evidence(PayEntOutbox event) {
        return new Evidence(null,null,null,"BRIDGE_RETRIES_EXHAUSTED",null,
                new DeliveryEvidence(event.getEventNo(),event.getEventType(),event.getStatus(),event.getAttempts()));
    }
    private ExceptionView view(PayEntException row) {
        return new ExceptionView(row.getExceptionNo(),row.getEntityNo(),row.getKind(),row.getBizNo(),row.getStatus(),
                json.readValue(row.getEvidence(),Evidence.class),row.getLastAction(),row.getVersion()).withDocumentNo(documents.find(DocumentType.ENTITLEMENT_EXCEPTION, TENANT, row.getExceptionNo()));
    }
}
