package ai.neargo.shop.reportbridge.api;

import ai.neargo.shop.svc.ReportServiceResponses;
import org.springframework.web.bind.annotation.ExceptionHandler;

import ai.neargo.shop.spi.report.ReportQueryInternalApi;
import ai.neargo.shop.spi.report.ReportQueryPort;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled"},havingValue="true")
public class InternalReportQueryController {
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> reportFailure(RuntimeException failure) {
        return ReportServiceResponses.error(failure);
    }

    private final ReportQueryPort service;
    private final String token;
    private final ObjectProvider<BizIdentityResolver> identities;
    private final ReportRealtimePort realtime;
    private final ReportMetricCatalogPort catalog;
    public InternalReportQueryController(ReportQueryPort service,ReportRealtimePort realtime,ReportMetricCatalogPort catalog,ObjectProvider<BizIdentityResolver> identities,@Value("${shop.services.internal-token:}") String token) {
        this.service=service; this.token=token; this.realtime=realtime; this.catalog=catalog; this.identities=identities;
    }
    @PostMapping("/internal/reports/food/realtime")
    public ResponseEntity<?> realtime(@RequestBody ReportRealtimePort.Query query,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()-> { authorize(query.scope(),query.actorNo(),query.inventoryOnly()); return realtime.summary(query); });
    }
    @PostMapping("/internal/reports/food/metrics")
    public ResponseEntity<?> metrics(@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,catalog::metrics);
    }
    @PostMapping(ReportQueryInternalApi.QUERY)
    public ResponseEntity<?> query(@RequestBody ReportQueryPort.Query query,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()-> {
            boolean stock=query.aggregationSet().startsWith("INVENTORY_") || query.aggregationSet().equals("MATERIAL_USAGE_DAY");
            query.scopes().forEach(scope->authorize(scope,query.actorNo(),stock));
            return service.query(query);
        });
    }
    private void authorize(Scope scope,String actor,boolean inventory) {
        var resolver=identities.getIfUnique();
        if (resolver==null || !"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var identity=resolver.resolve(actor,scope.storeNo());
        if (!scope.entityNo().equals(identity.merchantNo()) || !scope.storeNo().equals(identity.currentStoreNo())
                || !identity.storeNos().contains(scope.storeNo()) || !identity.can(BizPerms.FINANCE)
                || inventory && !identity.can(BizPerms.STOCK)) throw BizException.of(ErrorCode.FORBIDDEN);
    }
}
