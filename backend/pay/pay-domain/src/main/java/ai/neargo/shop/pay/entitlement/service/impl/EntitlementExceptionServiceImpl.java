package ai.neargo.shop.pay.entitlement.service.impl;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import java.util.List;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.Evidence;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.entity.PayEntException;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.ExceptionOpened;
import java.time.ZoneOffset;
import ai.neargo.shop.pay.entitlement.entity.PayEntExceptionAction;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.pay.mapper.entitlement.PayEntExceptionActionMapper;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.PayEntExceptionMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntGrantMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
@Service
public class EntitlementExceptionServiceImpl implements EntitlementExceptionService {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    private final PayEntExceptionMapper exceptions;
    private final EntGrantMapper grants;
    private final ObjectMapper json;
    private final PayEntExceptionActionMapper actions;
    private final PayEntitlementEventPublisher events;
    public EntitlementExceptionServiceImpl(PayEntExceptionMapper exceptions,EntGrantMapper grants,
            PayEntExceptionActionMapper actions,PayEntitlementEventPublisher events,ObjectMapper json) {
        this.events=events;
        this.actions=actions;
        this.exceptions=exceptions; this.grants=grants; this.json=json;
    }
    @Override @Transactional("payTxManager")
    public ExceptionView captureGrantFailure(EntitlementContext ctx,String entity,String no) {
        if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var grant=grants.lock(ctx.tenantNo(),no);
        if(grant==null || !entity.equals(grant.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        var previous=exceptions.findGrant(ctx.tenantNo(),no);
        if(previous!=null) return view(previous);
        if(!"PREPARED".equals(grant.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
        var source=json.readValue(grant.getSourceSnapshot(),SourceRef.class);
        var row=new PayEntException(); EntitlementRecords.audit(row,ctx);
        row.setExceptionNo("EX"+EntitlementRecords.digest(ctx.tenantNo()+":GRANT_DELIVERY:"+no).substring(0,60));
        row.setEntityNo(entity); row.setKind("GRANT_DELIVERY"); row.setBizNo(no);
        row.setEvidence(json.writeValueAsString(new Evidence(no,grant.getStatus(),source.sourceType(),"DELIVERY_FAILED")));
        exceptions.claim(row);
        documents.bind(DocumentType.ENTITLEMENT_EXCEPTION, ctx.tenantNo(), row.getExceptionNo());
        events.publish(new ExceptionOpened("EE"+EntitlementRecords.digest(row.getExceptionNo()).substring(0,60),
                row.getExceptionNo(),entity,row.getKind(),no,row.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()));
        return view(exceptions.findGrant(ctx.tenantNo(),no));
    }
    @Override public ExceptionView get(CatalogScope scope,String no) {
        platform(scope);
        String sourceNo=documents.internal(DocumentType.ENTITLEMENT_EXCEPTION, scope.tenantNo(), no);
        var row=exceptions.selectOne(Wrappers.<PayEntException>lambdaQuery()
                .eq(PayEntException::getTenantNo,scope.tenantNo()).eq(PayEntException::getEntityNo,scope.entityNo())
                .eq(PayEntException::getExceptionNo,sourceNo));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return view(row);
    }
    @Override public PageData<ExceptionView> list(CatalogScope scope,String kind,String status,long page,long size) {
        platform(scope);
        return listAcross(new OperatorEntityScope(scope.tenantNo(), scope.actorNo(), java.util.Set.of(scope.entityNo())),
                kind, status, page, size);
    }
    @Override public PageData<ActionView> actions(CatalogScope scope,String no,long page,long size) {
        var exception = get(scope,no);
        if(page<1 || page>1000000 || size<1 || size>100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result=actions.selectPage(new Page<>(page,size),Wrappers.<PayEntExceptionAction>lambdaQuery()
                .eq(PayEntExceptionAction::getTenantNo,scope.tenantNo())
                .eq(PayEntExceptionAction::getEntityNo,scope.entityNo())
                .eq(PayEntExceptionAction::getExceptionNo,exception.exceptionNo()).orderByDesc(PayEntExceptionAction::getId));
        var records = documents.project(DocumentType.ENTITLEMENT_EXCEPTION_ACTION, result.getRecords(),
                PayEntExceptionAction::getTenantNo, PayEntExceptionAction::getActionNo,
                a -> new ActionView(a.getActionNo(), a.getExceptionNo(), a.getOperationKind(), a.getStatus(),
                        a.getActorNo(), a.getReason(), a.getCreatedAt(), a.getUpdatedAt())
                        .withDocumentNo(documents.find(DocumentType.ENTITLEMENT_EXCEPTION_ACTION, a.getTenantNo(), a.getActionNo())));
        return new PageData<>(records, result.getTotal(), page, size);
    }
    private ExceptionView view(PayEntException row) {
        return new ExceptionView(row.getExceptionNo(),row.getEntityNo(),row.getKind(),row.getBizNo(),row.getStatus(),
                json.readValue(row.getEvidence(),Evidence.class),row.getLastAction(),row.getVersion()).withDocumentNo(documents.find(DocumentType.ENTITLEMENT_EXCEPTION, row.getTenantNo(), row.getExceptionNo()));
    }
    private static void platform(CatalogScope scope) {
        if(!scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    @Override public PageData<ExceptionView> listAcross(OperatorEntityScope scope, String kind, String status,
                                                       long page, long size) {
        if (!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (status != null && !java.util.Set.of("OPEN", "PROCESSING", "RESOLVED").contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (scope.entityNos() != null && scope.entityNos().isEmpty()) return new PageData<>(List.of(), 0, page, size);
        var query = Wrappers.<PayEntException>lambdaQuery().eq(PayEntException::getTenantNo, scope.tenantNo())
                .eq(kind != null, PayEntException::getKind, kind).eq(status != null, PayEntException::getStatus, status)
                .orderByDesc(PayEntException::getCreatedAt, PayEntException::getId);
        if (scope.entityNos() != null) query.in(PayEntException::getEntityNo, scope.entityNos());
        var result = exceptions.selectPage(new Page<>(page, size), query);
        return new PageData<>(documents.project(DocumentType.ENTITLEMENT_EXCEPTION, result.getRecords(), PayEntException::getTenantNo, PayEntException::getExceptionNo, this::view), result.getTotal(), page, size);
    }
}
