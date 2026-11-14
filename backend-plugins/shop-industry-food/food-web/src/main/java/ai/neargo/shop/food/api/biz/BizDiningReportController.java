package ai.neargo.shop.food.api.biz;
import ai.neargo.shop.svc.ReportServiceResponses;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.service.DiningReportService.View;
import ai.neargo.shop.food.service.DiningReportService.Granularity;
import ai.neargo.shop.food.service.DiningReportService.InventoryView;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name="shop.report.food.enabled",havingValue="true")
@Conditional(PolicyEnabled.class)
public class BizDiningReportController {
    private final DiningReportService service;
    private final FoodBizStoreScope scope;
    public BizDiningReportController(DiningReportService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service; scope=new FoodBizStoreScope(stores,validator);
    }
    @GetMapping("/biz/x/food/operating-reports/metrics")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public List<MetricDefinition> metrics(HttpServletRequest request) {
        scope.current(request); return service.metrics();
    }
    @GetMapping("/biz/x/food/operating-reports/inventory/realtime")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"') and @perm.canBiz('"+BizPerms.STOCK+"')")
    public ReportRealtimePort.Result inventoryRealtime(@RequestParam(defaultValue="1") int metricVersion,HttpServletRequest request) {
        validateRealtimeParameters(request);
        var context=scope.current(request);
        return service.realtime(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),metricVersion,true);
    }
    @GetMapping("/biz/x/food/operating-reports/realtime")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public ReportRealtimePort.Result realtime(@RequestParam(defaultValue="1") int metricVersion,HttpServletRequest request) {
        validateRealtimeParameters(request);
        var context=scope.current(request);
        return service.realtime(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),metricVersion,false);
    }
    @GetMapping("/biz/x/food/operating-reports/{view:daily|period|trend|payments|discounts|refunds|profit|rankings|inventory|health}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public Result query(@PathVariable String view,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) LocalDate businessDate,@RequestParam(defaultValue="DAY") Granularity granularity,@RequestParam(defaultValue="BALANCE") InventoryView inventoryView,@RequestParam(required=false) String unit,@RequestParam(required=false) String currency,
            @RequestParam(required=false) List<String> storeNos,@RequestParam(required=false) Integer metricVersion,
            @RequestParam(required=false) Set<String> metricCodes,@RequestParam(defaultValue="1000") int limit,
            HttpServletRequest request) {
        var context=scope.current(request);
        if ("daily".equals(view)) {
            if (businessDate==null || from!=null || to!=null) throw BizException.of(ErrorCode.BAD_REQUEST);
            from=businessDate; to=businessDate.plusDays(1);
        } else if (businessDate!=null || from==null || to==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var names=request.getParameterNames();
        var allowed=Set.of("from","to","businessDate","storeNos","metricVersion","metricCodes","limit","granularity","inventoryView","unit","currency");
        while (names.hasMoreElements()) if (!allowed.contains(names.nextElement())) throw BizException.of(ErrorCode.BAD_REQUEST);
        var selected=storeNos==null?List.of(context.currentStoreNo()):List.copyOf(storeNos);
        if (selected.isEmpty() || selected.size()>30 || Set.copyOf(selected).size()!=selected.size())
            throw BizException.of(ErrorCode.BAD_REQUEST);
        boolean inventory="inventory".equals(view);
        for (String store:selected) {
            if (!context.storeNos().contains(store) || !context.withStore(store).can(BizPerms.FINANCE)
                    || inventory && !context.withStore(store).can(BizPerms.STOCK)) throw BizException.of(ErrorCode.FORBIDDEN);
        }
        View selectedView=View.valueOf(view.toUpperCase(Locale.ROOT));
        if(selectedView!=View.INVENTORY && inventoryView!=InventoryView.BALANCE) throw BizException.of(ErrorCode.BAD_REQUEST);
        if(selectedView==View.INVENTORY && inventoryView==InventoryView.MOVEMENT) selectedView=View.INVENTORY_MOVEMENT;
        if(selectedView!=View.TREND && granularity!=Granularity.DAY) throw BizException.of(ErrorCode.BAD_REQUEST);
        if(selectedView==View.TREND) selectedView=switch(granularity) { case DAY->View.TREND;case WEEK->View.TREND_WEEK;case MONTH->View.TREND_MONTH; };
        return service.query(context.merchantNo(),selected,SecurityUtils.currentUserNo(),selectedView,
                from,to,metricVersion,metricCodes,limit,ReportServiceResponses.domain(()->new MeasureFilter(currency,unit)));
    }
    static void validateRealtimeParameters(HttpServletRequest request) {
        var names=request.getParameterNames();
        while(names.hasMoreElements()) if(!"metricVersion".equals(names.nextElement()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

}
