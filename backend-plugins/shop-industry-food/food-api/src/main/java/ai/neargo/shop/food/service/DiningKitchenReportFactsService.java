package ai.neargo.shop.food.service;

import ai.neargo.shop.spi.report.ReportKitchenEventDayPort;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;

/** Reads original kitchen action quantities without assigning inventory or financial meaning. */
public interface DiningKitchenReportFactsService
        extends ReportSourceFactsPort, ReportKitchenEventDayPort {}
