package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.PeriodSummaryService;
import ai.neargo.inventory.domain.service.PeriodSummaryService.Row;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 期间汇总（TDD §2.11）：宿主的统一库存报表取仓内这一段。 */
@RestController
public class ReportController {

    private final PeriodSummaryService summary;

    public ReportController(PeriodSummaryService summary) {
        this.summary = summary;
    }

    @GetMapping("/api/inventory/v1/reports/period-summary")
    public List<Row> periodSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String locationId,
            @RequestParam(required = false) String itemId) {
        return summary.summarize(InventoryRequestContext.principal().ownerId(), from, to, locationId, itemId);
    }
}
