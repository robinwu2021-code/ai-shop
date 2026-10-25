package ai.neargo.shop.food.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.food.service.DiningReportService.View;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Public criteria only; actor, entity and tenant are supplied by the original authenticated context. */
public record DiningReportExportReq(@NotNull View view,LocalDate from,LocalDate to,LocalDate businessDate,
        @Size(max=30) List<String> storeNos,Integer metricVersion,@Size(max=64) Set<String> metricCodes,Integer limit,MeasureFilter measure) {}
