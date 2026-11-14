package ai.neargo.shop.food.server.api;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.spi.FoodStockReportInternalApi;
import ai.neargo.shop.spi.food.FoodStockReportFactsPort;
import ai.neargo.shop.spi.report.ReportContracts.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
@RestController @ConditionalOnFoodEnabled
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class InternalFoodStockReportController extends FoodStockInternalErrors {
    private final org.springframework.beans.factory.ObjectProvider<FoodStockReportFactsPort> service;
    public InternalFoodStockReportController(org.springframework.beans.factory.ObjectProvider<FoodStockReportFactsPort> service){this.service=service;}
    @PostMapping(FoodStockReportInternalApi.FACTS) public SourceSnapshot facts(@RequestBody SourceQuery query){var source=service.getIfUnique();
        if(source==null)throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        return source.readSnapshot(query);}
}
