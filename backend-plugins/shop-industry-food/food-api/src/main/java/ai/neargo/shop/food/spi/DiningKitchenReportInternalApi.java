package ai.neargo.shop.food.spi;

import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportKitchenEventDayPort;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Uses the existing authenticated FOOD report transport for original kitchen facts. */
public interface DiningKitchenReportInternalApi {
    String AFFECTED_DAY = "/internal/food/operating-report/kitchen-affected-day";

    @PostExchange(AFFECTED_DAY)
    ReportKitchenEventDayPort.AffectedDay affectedDay(
            @RequestBody ReportKitchenEventDayPort.Change change);

    String FACTS = "/internal/food/operating-report/kitchen-facts";

    @PostExchange(FACTS)
    SourceSnapshot facts(@RequestBody SourceQuery query);
}
