package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.DiningKitchenReportFactsService;
import ai.neargo.shop.food.spi.DiningKitchenReportInternalApi;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportKitchenEventDayPort;
import ai.neargo.shop.svc.ReportServiceResponses;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Shares the original FOOD internal service authentication chain. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningKitchenReportController {
    private final DiningKitchenReportFactsService facts;

    public InternalDiningKitchenReportController(DiningKitchenReportFactsService facts) {
        this.facts = facts;
    }

    @PostMapping(DiningKitchenReportInternalApi.AFFECTED_DAY)
    public ReportKitchenEventDayPort.AffectedDay affectedDay(
            @RequestBody ReportKitchenEventDayPort.Change change) {
        return facts.affectedDay(change);
    }

    @PostMapping(DiningKitchenReportInternalApi.FACTS)
    public SourceSnapshot facts(@RequestBody SourceQuery query) {
        return facts.readSnapshot(query);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> failure(RuntimeException failure) {
        return ReportServiceResponses.error(failure);
    }
}
