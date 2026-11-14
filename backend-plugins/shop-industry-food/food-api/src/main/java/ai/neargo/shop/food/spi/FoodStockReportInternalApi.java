package ai.neargo.shop.food.spi;
import ai.neargo.shop.spi.report.ReportContracts.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
public interface FoodStockReportInternalApi {
    String FACTS="/internal/food/operating-report/local-stock-facts";
    @PostExchange(FACTS) SourceSnapshot facts(@RequestBody SourceQuery query);
}
