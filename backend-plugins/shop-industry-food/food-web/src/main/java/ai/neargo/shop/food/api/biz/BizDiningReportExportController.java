package ai.neargo.shop.food.api.biz;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningReportExportReq;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.service.DiningReportService.View;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@ConditionalOnProperty(name={"shop.report.food.enabled","shop.report.export.enabled"},havingValue="true")
public class BizDiningReportExportController {
    private final DiningReportService reports;
    private final FoodBizStoreScope scope;
    public BizDiningReportExportController(DiningReportService reports,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.reports=reports; scope=new FoodBizStoreScope(stores,validator);
    }
    @PostMapping("/biz/x/food/operating-reports/exports")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')")
    public ReportExportPort.Task create(@Valid @RequestBody DiningReportExportReq body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var context=scope.current(request);
        var stores=body.storeNos()==null?List.of(context.currentStoreNo()):List.copyOf(body.storeNos());
        if (stores.isEmpty() || stores.size()>30 || Set.copyOf(stores).size()!=stores.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
        for (String store:stores) if (!context.storeNos().contains(store) || !context.withStore(store).can(BizPerms.FINANCE)
                || !context.withStore(store).can(BizPerms.REPORT_EXPORT)
                || (body.view()==View.INVENTORY || body.view()==View.INVENTORY_MOVEMENT) && !context.withStore(store).can(BizPerms.STOCK)) throw BizException.of(ErrorCode.FORBIDDEN);
        LocalDate from=body.from(),to=body.to();
        if (body.view()==View.DAILY) {
            if (body.businessDate()==null || from!=null || to!=null) throw BizException.of(ErrorCode.BAD_REQUEST);
            from=body.businessDate();to=from.plusDays(1);
        } else if (body.businessDate()!=null || from==null || to==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        return reports.export(context.merchantNo(),stores,SecurityUtils.currentUserNo(),body.view(),from,to,body.metricVersion(),
                body.metricCodes(),body.limit()==null?1000:body.limit(),key,body.measure());
    }
    @GetMapping("/biz/x/food/operating-reports/exports/{exportNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')")
    public ReportExportPort.Task get(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String exportNo,HttpServletRequest request) {
        var context=scope.current(request);return reports.getExport(context.merchantNo(),SecurityUtils.currentUserNo(),exportNo);
    }
    @GetMapping("/biz/x/food/operating-reports/exports/{exportNo}/download")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.REPORT_EXPORT+"')")
    public ResponseEntity<ByteArrayResource> download(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String exportNo,HttpServletRequest request) {
        var context=scope.current(request);var result=reports.downloadExport(context.merchantNo(),SecurityUtils.currentUserNo(),exportNo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(exportNo+".csv").build().toString())
                .body(new ByteArrayResource(result.content()));
    }
}
