package ai.neargo.shop.spi.food;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
/** Selected by the restaurant report coordinator; deliberately not a competing global report source. */
public interface FoodStockReportFactsPort {SourceSnapshot readSnapshot(SourceQuery query);}
