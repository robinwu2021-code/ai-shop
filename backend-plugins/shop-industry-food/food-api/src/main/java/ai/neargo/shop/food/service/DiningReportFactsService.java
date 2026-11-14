package ai.neargo.shop.food.service;
import ai.neargo.shop.spi.report.ReportAffectedDaysPort;

import ai.neargo.shop.spi.report.ReportDayPort;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;

/** Food-owned source read, distinct from the published operating-report query service. */
public interface DiningReportFactsService extends ReportSourceFactsPort, ReportDayPort, ReportAffectedDaysPort {}
